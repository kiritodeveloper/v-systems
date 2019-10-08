package vsys.utils

import java.util.concurrent.locks.ReentrantReadWriteLock

import vsys.utils.Synchronized.{ReadLock, _}

import scala.util.DynamicVariable
// http://vlkan.com/blog/post/2015/09/09/enforce-locking/

object Synchronized {

  sealed trait TypedLock {

    def lock(): Unit

    def unlock(): Unit

    def tryLock(): Boolean
  }

  sealed class ReadLock(rwl: ReentrantReadWriteLock) extends TypedLock {

    override def lock(): Unit = rwl.readLock().lock()

    override def unlock(): Unit = rwl.readLock().unlock()

    override def tryLock(): Boolean = rwl.readLock().tryLock()

    override def toString: String = "read of " + rwl.toString
  }

  sealed class WriteLock(rwl: ReentrantReadWriteLock) extends ReadLock(rwl) {
    override def lock(): Unit = rwl.writeLock.lock()

    override def unlock(): Unit = rwl.writeLock.unlock()

    override def tryLock(): Boolean = rwl.writeLock.tryLock()

    override def toString: String = "write of " + rwl.toString
  }

}

trait Synchronized {

  def synchronizationToken: ReentrantReadWriteLock

  private lazy val instanceReadLock: ReadLock = new ReadLock(synchronizationToken)

  private lazy val instanceReadWriteLock: WriteLock = new WriteLock(synchronizationToken)

  protected case class Synchronized[T](private val initialValue: T) {

    private val value = new DynamicVariable(initialValue)

    def apply()(implicit readLock: ReadLock): T = {
      value.value
    }

    def mutate[R](f: T => R)(implicit readWriteLock: WriteLock): R = {
      f(value.value)
    }

    def transform(newVal: T => T)(implicit readWriteLock: WriteLock): T = {
      value.value = newVal(value.value)
      value.value
    }

    def set(newVal: => T)(implicit readWriteLock: WriteLock): T = {
      val oldVal = value.value
      value.value = newVal
      oldVal
    }
  }

  def read[T](body: ReadLock => T): T =
    synchronizeOperation(instanceReadLock)(body)

  protected def write[T](body: WriteLock => T): T =
    synchronizeOperation(instanceReadWriteLock)(body)

  protected def synchronizeOperation[T, L <: TypedLock](lock: L)(body: L => T): T = {
    lock.lock()
    try {
      body(lock)
    }
    finally {
      lock.unlock()
    }
  }
}

trait SynchronizedOne extends Synchronized {
  val synchronizationToken: ReentrantReadWriteLock = new ReentrantReadWriteLock()
}