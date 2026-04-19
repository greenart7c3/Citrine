package com.greenart7c3.citrine.storage.lmdb

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Named sub-database inside an [LmdbEnv]. Backed by a sorted in-memory map
 * (guaranteeing O(log n) inserts/lookups and ordered iteration — the key
 * LMDB properties callers rely on) plus an append-only journal file. On
 * clean close the journal is compacted into a snapshot; on open either the
 * snapshot or the full journal is replayed.
 *
 * Append format: one record per line, either
 *   P\t<encodedKey>\t<encodedValue>   (put)
 *   D\t<encodedKey>                   (delete)
 *
 * Values and keys are expected to be newline-free strings (the caller's
 * codec is responsible for escaping).
 */
class LmdbDbi<K : Comparable<K>, V : Any> internal constructor(
    private val env: LmdbEnv,
    private val name: String,
    private val codec: LmdbCodec<K, V>,
) {
    private val map = ConcurrentSkipListMap<K, V>()
    private val dirty = AtomicBoolean(false)
    private val dataFile: File = File(env.path, "$name.mdb")
    private val journalFile: File = File(env.path, "$name.log")

    @Volatile private var journalWriter: BufferedWriter? = null

    @Volatile private var pendingWrites = 0
    private val compactEvery = 10_000

    internal fun load() {
        map.clear()
        if (dataFile.exists()) {
            dataFile.useLinesSafely { line ->
                val sep = line.indexOf('\t')
                if (sep > 0) {
                    val k = codec.decodeKey(line.substring(0, sep))
                    val v = codec.decodeValue(line.substring(sep + 1))
                    map[k] = v
                }
            }
        }
        if (journalFile.exists()) {
            journalFile.useLinesSafely { line ->
                if (line.isEmpty()) return@useLinesSafely
                val op = line[0]
                val rest = line.substring(2)
                when (op) {
                    'P' -> {
                        val sep = rest.indexOf('\t')
                        if (sep > 0) {
                            val k = codec.decodeKey(rest.substring(0, sep))
                            val v = codec.decodeValue(rest.substring(sep + 1))
                            map[k] = v
                        }
                    }
                    'D' -> {
                        val k = codec.decodeKey(rest)
                        map.remove(k)
                    }
                }
            }
        }
    }

    private fun journal(): BufferedWriter {
        var w = journalWriter
        if (w == null) {
            w = BufferedWriter(OutputStreamWriter(FileOutputStream(journalFile, true), Charsets.UTF_8))
            journalWriter = w
        }
        return w
    }

    fun put(txn: LmdbTxn, key: K, value: V) {
        require(txn.write) { "put requires a write transaction" }
        map[key] = value
        val w = journal()
        w.write("P\t")
        w.write(codec.encodeKey(key))
        w.write("\t")
        w.write(codec.encodeValue(value))
        w.newLine()
        dirty.set(true)
        pendingWrites++
        if (pendingWrites >= compactEvery) compact()
    }

    fun delete(txn: LmdbTxn, key: K): Boolean {
        require(txn.write) { "delete requires a write transaction" }
        val removed = map.remove(key) != null
        if (removed) {
            val w = journal()
            w.write("D\t")
            w.write(codec.encodeKey(key))
            w.newLine()
            dirty.set(true)
            pendingWrites++
        }
        return removed
    }

    fun clear(txn: LmdbTxn) {
        require(txn.write) { "clear requires a write transaction" }
        map.clear()
        // rewrite empty data/journal on next flush
        journalWriter?.close()
        journalWriter = null
        dataFile.delete()
        journalFile.delete()
        pendingWrites = 0
        dirty.set(false)
    }

    fun get(@Suppress("UNUSED_PARAMETER") txn: LmdbTxn, key: K): V? = map[key]

    fun containsKey(@Suppress("UNUSED_PARAMETER") txn: LmdbTxn, key: K): Boolean = map.containsKey(key)

    fun size(): Int = map.size

    /** Iterate entries in ascending key order. */
    fun iterateAscending(@Suppress("UNUSED_PARAMETER") txn: LmdbTxn): Sequence<Map.Entry<K, V>> = map.entries.asSequence()

    /** Iterate entries in descending key order. */
    fun iterateDescending(@Suppress("UNUSED_PARAMETER") txn: LmdbTxn): Sequence<Map.Entry<K, V>> = map.descendingMap().entries.asSequence()

    fun subMap(from: K, to: K): Map<K, V> = map.subMap(from, true, to, true)

    fun keys(): Set<K> = map.keys

    fun values(): Collection<V> = map.values

    fun entries(): Sequence<Map.Entry<K, V>> = map.entries.asSequence()

    internal fun flushIfDirty() {
        if (dirty.getAndSet(false)) {
            journalWriter?.flush()
        }
    }

    internal fun close() {
        try {
            journalWriter?.flush()
            journalWriter?.close()
            journalWriter = null
        } catch (_: Throwable) {
        }
        compact()
    }

    /** Rewrites the snapshot from the in-memory map and discards the journal. */
    private fun compact() {
        try {
            val tmp = File(dataFile.parentFile, "$name.mdb.tmp")
            journalWriter?.close()
            journalWriter = null
            BufferedWriter(OutputStreamWriter(FileOutputStream(tmp), Charsets.UTF_8)).use { w ->
                for ((k, v) in map) {
                    w.write(codec.encodeKey(k))
                    w.write("\t")
                    w.write(codec.encodeValue(v))
                    w.newLine()
                }
            }
            if (dataFile.exists()) dataFile.delete()
            tmp.renameTo(dataFile)
            journalFile.delete()
            pendingWrites = 0
        } catch (_: Throwable) {
        }
    }
}

private inline fun File.useLinesSafely(crossinline onLine: (String) -> Unit) {
    BufferedReader(InputStreamReader(FileInputStream(this), Charsets.UTF_8)).use { reader ->
        reader.lineSequence().forEach(onLine)
    }
}
