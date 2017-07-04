package scorex.transaction

import java.util.concurrent.locks.ReentrantReadWriteLock

import com.wavesplatform.state2._
import scorex.block.Block.BlockId
import scorex.block.{Block, MicroBlock}
import scorex.transaction.History.BlockchainScore
import scorex.transaction.ValidationError.{BlockAppendError, MicroBlockAppendError}

trait NgHistoryWriter extends HistoryWriter with NgHistory {
  def appendMicroBlock(microBlock: MicroBlock)(fullBlockConsensusValidation: => Either[ValidationError, Unit]): Either[ValidationError, Unit]

  def bestLiquidBlock(): Option[Block]

  def forgeBlock(id: BlockId): Option[Block]

  def liquidBlockExists(): Boolean
}

class NgHistoryWriterImpl(inner: HistoryWriter) extends NgHistoryWriter {

  override def synchronizationToken: ReentrantReadWriteLock = inner.synchronizationToken

  private val baseBlock = Synchronized(Option.empty[Block])
  private val micros = Synchronized(List.empty[MicroBlock])

  def liquidBlockExists(): Boolean = read { implicit l =>
    baseBlock().isDefined
  }

  def bestLiquidBlock(): Option[Block] = read { implicit l =>
    baseBlock().map(base => {
      val ms = micros()
      if (ms.isEmpty) {
        base
      } else {
        base.copy(
          signerData = base.signerData.copy(signature = ms.head.totalResBlockSig),
          transactionData = base.transactionData ++ ms.map(_.transactionData).reverse.flatten)
      }
    })
  }

  override def appendBlock(block: Block)(consensusValidation: => Either[ValidationError, BlockDiff]): Either[ValidationError, BlockDiff]
  = write { implicit l => {
    if (baseBlock().isEmpty) {
      if (inner.lastBlock.exists(_.uniqueId != block.reference))
        Left(BlockAppendError("References incorrect or non-existing block (inner block exists, liquid block doesn't)", block))
      else
        consensusValidation
    }
    else forgeBlock(block.reference) match {
      case Some(forgedBlock) =>
        inner.appendBlock(forgedBlock)(consensusValidation)
      case None =>
        Left(BlockAppendError("References incorrect or non-existing block (liquid block exists)", block))
    }
  }.map { bd => // finally place new as liquid
    micros.set(List.empty)
    baseBlock.set(Some(block))
    bd
  }
  }

  override def discardBlock(): Unit = write { implicit l =>
    if (baseBlock().isDefined) {
      baseBlock.set(None)
      micros.set(List.empty)
    } else {
      inner.discardBlock()
    }
  }

  override def height(): Int = read { implicit l =>
    inner.height() + baseBlock().map(_ => 1).getOrElse(0)
  }

  override def blockBytes(height: Int): Option[Array[Byte]] = read { implicit l =>
    inner.blockBytes(height).orElse(if (height == inner.height() + 1) bestLiquidBlock().map(_.bytes) else None)
  }

  override def scoreOf(blockId: BlockId): Option[BlockchainScore] = read { implicit l =>
    inner.scoreOf(blockId)
      .orElse(if (containsLocalBlock(blockId))
        Some(inner.score() + baseBlock().get.blockScore)
      else None)
  }

  override def heightOf(blockId: BlockId): Option[Int] = read { implicit l =>
    lazy val innerHeight = inner.height()
    inner.heightOf(blockId).orElse(if (containsLocalBlock(blockId))
      Some(innerHeight + 1)
    else
      None)
  }

  override def lastBlockIds(howMany: Int): Seq[BlockId] = read { implicit l =>
    baseBlock() match {
      case Some(base) =>
        micros().headOption.map(_.totalResBlockSig).getOrElse(base.uniqueId) +: inner.lastBlockIds(howMany - 1)
      case None =>
        inner.lastBlockIds(howMany)
    }
  }

  override def appendMicroBlock(microBlock: MicroBlock)
                               (fullBlockConsensusValidation: => Either[ValidationError, Unit]): Either[ValidationError, Unit] = write { implicit l =>
    baseBlock() match {
      case None =>
        Left(MicroBlockAppendError("No base block exists", microBlock))
      case Some(base) if base.signerData.generator != microBlock.generator =>
        Left(MicroBlockAppendError("Base block has been generated by another account", microBlock))
      case Some(base) =>
        micros().headOption match {
          case None if base.uniqueId != microBlock.prevResBlockSig =>
            Left(MicroBlockAppendError("It's first micro and it doesn't reference base block(which exists)", microBlock))
          case Some(prevMicro) if prevMicro.totalResBlockSig != microBlock.prevResBlockSig =>
            Left(MicroBlockAppendError("It doesn't reference last known microBlock(which exists)", microBlock))
          case _ =>
            Signed.validateSignatures(microBlock)
              .flatMap(_ => fullBlockConsensusValidation)
              .map { _ =>
                micros.set(microBlock +: micros())
                Right(())
              }
        }
    }
  }

  private def containsLocalBlock(blockId: BlockId): Boolean = read { implicit l =>
    baseBlock().find(_.uniqueId == blockId)
      .orElse(micros().find(_.totalResBlockSig == blockId)).isDefined
  }

  def forgeBlock(id: BlockId): Option[Block] = read { implicit l =>
    baseBlock().flatMap(base => {
      lazy val ms = micros()
      if (base.uniqueId == id) {
        Some(base)
      } else if (!ms.exists(_.totalResBlockSig == id)) None
      else {
        val (txs, found) = ms.reverse.foldLeft((List.empty[Transaction], false)) { case ((accumulated, matched), micro) =>
          if (matched)
            (accumulated, true)
          else if (micro.totalResBlockSig == id)
            (accumulated ++ micro.transactionData, true)
          else
            (accumulated ++ micro.transactionData, false)
        }
        assert(found)
        Some(base.copy(
          signerData = base.signerData.copy(signature = ms.head.totalResBlockSig),
          transactionData = base.transactionData ++ txs))
      }
    })
  }

  override def microBlock(id: BlockId): Option[MicroBlock] = read { implicit l =>
    micros().find(_.totalResBlockSig == id)
  }
  override def close(): Unit = inner.close()
}
