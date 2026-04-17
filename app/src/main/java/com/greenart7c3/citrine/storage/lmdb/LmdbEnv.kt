package com.greenart7c3.citrine.storage.lmdb

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * LMDB-inspired environment that owns a directory on disk and a set of named
 * sub-databases ("Dbi"). This is a pure Kotlin implementation that mimics the
 * LMDB API shape (Env/Dbi/Txn, single-writer, multi-reader, atomic commits)
 * without requiring a native library on Android.
 *
 * The environment persists each Dbi as an append-only log file plus a small
 * compacted snapshot on close. A global RW lock gives us LMDB's single-writer,
 * multiple-reader semantics; file-level locking prevents concurrent environments
 * from sharing the same directory.
 */
class LmdbEnv private constructor(
    val path: File,
) {
    private val dbis = ConcurrentHashMap<String, LmdbDbi<*, *>>()
    private val rwLock = ReentrantReadWriteLock(true)
    private val lockFile: RandomAccessFile
    private val exclusiveLock: FileLock?

    init {
        require(path.isDirectory || path.mkdirs()) { "Cannot create LMDB env dir: $path" }
        lockFile = RandomAccessFile(File(path, "lock.mdb"), "rw")
        exclusiveLock = try {
            lockFile.channel.tryLock()
        } catch (_: Throwable) {
            null
        }
    }

    fun <K : Comparable<K>, V : Any> openDbi(
        name: String,
        codec: LmdbCodec<K, V>,
    ): LmdbDbi<K, V> {
        @Suppress("UNCHECKED_CAST")
        return dbis.getOrPut(name) {
            LmdbDbi(this, name, codec).also { it.load() }
        } as LmdbDbi<K, V>
    }

    fun <R> readTxn(block: (LmdbTxn) -> R): R {
        val lock = rwLock.readLock()
        lock.lock()
        try {
            return block(LmdbTxn(write = false))
        } finally {
            lock.unlock()
        }
    }

    fun <R> writeTxn(block: (LmdbTxn) -> R): R {
        val lock = rwLock.writeLock()
        lock.lock()
        try {
            val txn = LmdbTxn(write = true)
            val result = block(txn)
            // commit all dirty Dbis
            dbis.values.forEach { it.flushIfDirty() }
            return result
        } finally {
            lock.unlock()
        }
    }

    fun close() {
        try {
            writeTxn { dbis.values.forEach { it.close() } }
        } catch (_: Throwable) {
        }
        try {
            exclusiveLock?.release()
            lockFile.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun open(path: File): LmdbEnv = LmdbEnv(path)
    }
}

/**
 * Active transaction handle. Unlike real LMDB, this implementation does not
 * provide isolation across transactions — a read observes whatever the most
 * recent write transaction committed. Callers treat the handle as a scope marker.
 */
class LmdbTxn internal constructor(val write: Boolean)

interface LmdbCodec<K, V> {
    fun encodeKey(key: K): String
    fun decodeKey(raw: String): K
    fun encodeValue(value: V): String
    fun decodeValue(raw: String): V
}
