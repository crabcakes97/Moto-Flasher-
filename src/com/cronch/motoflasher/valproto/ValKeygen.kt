// lk_keygen.py — generate 20-char KEYs for --key-token-secret / erase-token.
// The seeded path reproduces CPython random.Random(seed) exactly (MT19937 +
// version-2 str seeding + _randbelow rejection sampling) so --seed runs are
// byte-identical to Python. Unseeded runs use SecureRandom (same distribution,
// different draws — as in Python with secrets.choice).
package com.cronch.motoflasher.valproto

import java.security.MessageDigest
import java.security.SecureRandom

/** CPython random.Random: MT19937 with version-2 seeding. */
class PythonRandom(seed: Any?) {
    private val mt = LongArray(624)
    private var mti = 624

    init {
        when (seed) {
            null -> {
                // Non-deterministic: seed from OS entropy (CPython seeds from
                // os.urandom when no seed is given).
                val bytes = ByteArray(8)
                SecureRandom().nextBytes(bytes)
                var v = 0L
                for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
                initGenRand(v and 0xFFFFFFFFL)
            }
            is Int -> initByArray(intToWords(seed.toLong()))
            is Long -> initByArray(intToWords(seed))
            is String -> {
                val a = seed.toByteArray(Charsets.UTF_8)
                val digest = MessageDigest.getInstance("SHA-512").digest(a)
                val combined = a + digest
                val big = combined.fold(0.toBigInteger()) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF).toBigInteger() }
                initByArray(bigIntToWords(big))
            }
            else -> throw IllegalArgumentException("unsupported seed type")
        }
    }

    private fun intToWords(v: Long): LongArray {
        // CPython: ints that fit in 32 bits seed via init_genrand directly.
        if (v in 0 until (1L shl 32)) {
            initGenRand(v)
            return LongArray(0) // marker: already seeded
        }
        return bigIntToWords(v.toBigInteger())
    }

    private fun bigIntToWords(big: java.math.BigInteger): LongArray {
        if (big.signum() == 0) return longArrayOf(0)
        val raw = big.toByteArray() // big-endian two's complement
        // Strip the sign-extension 0x00 byte BigInteger prepends when the
        // top bit is set, so the word count matches CPython's key length.
        val bytes = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        // little-endian 32-bit words
        val nWords = (bytes.size + 3) / 4
        val words = LongArray(nWords)
        for (i in bytes.indices) {
            val byteIdx = bytes.size - 1 - i
            val wordIdx = i / 4
            val shift = (i % 4) * 8
            words[wordIdx] = words[wordIdx] or ((bytes[byteIdx].toLong() and 0xFF) shl shift)
        }
        return words
    }

    private fun initGenRand(s: Long) {
        mt[0] = s and 0xFFFFFFFFL
        for (i in 1 until 624) {
            val prev = mt[i - 1]
            mt[i] = (1812433253L * (prev xor (prev shr 30)) + i) and 0xFFFFFFFFL
        }
        mti = 624
    }

    private fun initByArray(initKey: LongArray) {
        if (initKey.isEmpty()) return // intToWords already seeded via initGenRand
        initGenRand(19650218L)
        var i = 1
        var j = 0
        val keyLen = initKey.size
        val rounds = maxOf(624, keyLen)
        for (k in 0 until rounds) {
            val prev = mt[i - 1]
            // Parentheses matter: in Kotlin, infix xor/or bind LOOSER than
            // +/-, so (a xor b) + c must be written explicitly — CPython adds
            // init_key[j] + j AFTER the xor, not inside it.
            mt[i] = ((mt[i] xor ((prev xor (prev shr 30)) * 1664525L)) + initKey[j] + j) and 0xFFFFFFFFL
            i++
            j++
            if (i >= 624) {
                mt[0] = mt[623]
                i = 1
            }
            if (j >= keyLen) j = 0
        }
        for (k in 0 until 623) {
            val prev = mt[i - 1]
            mt[i] = ((mt[i] xor ((prev xor (prev shr 30)) * 1566083941L)) - i) and 0xFFFFFFFFL
            i++
            if (i >= 624) {
                mt[0] = mt[623]
                i = 1
            }
        }
        mt[0] = 0x80000000L
        mti = 624
    }

    private fun genrandUint32(): Long {
        if (mti >= 624) {
            for (i in 0 until 624) {
                val y = (mt[i] and 0x80000000L) or (mt[(i + 1) % 624] and 0x7FFFFFFFL)
                var v = mt[(i + 397) % 624] xor (y shr 1)
                if ((y and 1L) != 0L) v = v xor 0x9908B0DFL
                mt[i] = v and 0xFFFFFFFFL
            }
            mti = 0
        }
        var y = mt[mti++]
        y = y xor (y shr 11)
        y = y xor ((y shl 7) and 0x9D2C5680L)
        y = y xor ((y shl 15) and 0xEFC60000L)
        y = y xor (y shr 18)
        return y and 0xFFFFFFFFL
    }

    /** CPython getrandbits(k) for k <= 32 (all our uses: k <= 7). */
    fun getrandbits(k: Int): Long {
        require(k >= 0) { "negative getrandbits" }
        if (k == 0) return 0
        if (k <= 32) return genrandUint32() shr (32 - k)
        val words = (k + 31) / 32
        var r = 0L
        // CPython builds big ints; k > 32 never occurs in keygen, but keep a
        // bounded approximation for completeness (not byte-identical past 32).
        for (i in 0 until words) r = (r shl 32) or genrandUint32()
        return r shr (words * 32 - k)
    }

    fun randbelow(n: Int): Int {
        val k = 32 - Integer.numberOfLeadingZeros(n - 1) // n.bit_length()
        while (true) {
            val r = getrandbits(k)
            if (r < n) return r.toInt()
        }
    }

    fun choice(alphabet: String): Char = alphabet[randbelow(alphabet.length)]
}

object ValKeygen {
    fun randomChoice(alphabet: String, rng: PythonRandom?): Char {
        if (rng != null) return rng.choice(alphabet)
        val idx = SecureRandom().nextInt(alphabet.length)
        return alphabet[idx]
    }

    fun makeKey(constants: ByteArray, alphabet: String, rng: PythonRandom?): String {
        val alphabetSet = alphabet.map { it.code }.toSet()
        val left = mutableListOf<Int>()
        val right = mutableListOf<Int>()
        for (constant in constants) {
            var pair: Pair<Int, Int>? = null
            for (ignored in 0 until 5000) {
                val first = randomChoice(alphabet, rng).code
                val second = first xor (constant.toInt() and 0xFF)
                if (second in alphabetSet) {
                    pair = first to second
                    break
                }
            }
            val (first, second) = pair ?: throw IllegalArgumentException(
                "could not generate a KEY with the chosen alphabet; drop --safe to use full printable ASCII"
            )
            left.add(first)
            right.add(second)
        }
        val raw = (left + right.reversed()).map { it.toByte() }.toByteArray()
        require(raw.size == 20) { "KEY invalida generada: ${raw.size} bytes" }
        return String(raw, Charsets.US_ASCII)
    }

    fun uniqueKeys(constants: ByteArray, count: Int, alphabet: String, rng: PythonRandom?): List<String> {
        val seen = linkedSetOf<String>()
        while (seen.size < count) {
            val key = makeKey(constants, alphabet, rng)
            if (key in seen) continue
            seen.add(key)
        }
        return seen.toList()
    }

    data class KeygenRequest(
        val secret: String = "Valeria",
        val device: String = "",
        val imei: String? = null,
        val serialno: String? = null,
        val erasePartition: String? = null,
        val count: Int = 5,
        val alphabet: String = "alnum",
        val safe: Boolean = false,
        val seed: String? = null,
    )

    data class KeygenResult(
        val mode: String,
        val secret: String,
        val device: String,
        val deviceKind: String,
        val erasePartition: String?,
        val alphabet: String,
        val constants: ByteArray,
        val keys: List<String>,
    )

    /** Port of lk_keygen.py main() (without the prints; caller formats). */
    fun generate(req: KeygenRequest): KeygenResult {
        require(req.count >= 1) { "--count must be >= 1" }
        val alphabetName = if (req.safe) "alnum" else req.alphabet
        val erasePartition = if (req.erasePartition != null) ValAnalysis.parsePartitionName(req.erasePartition) else null
        val (device, deviceKind0) = ValAnalysis.resolveDeviceOrImei(req.device, req.imei)
        if (req.serialno != null && erasePartition != null) throw IllegalArgumentException("--serialno only applies to the KEY token, not to --erase-partition")
        if (req.serialno != null && (req.device.isNotEmpty() || req.imei != null)) throw IllegalArgumentException("use --serialno or --device/--imei, not both")
        var deviceKind = deviceKind0
        var deviceOut = device
        val constants: ByteArray
        if (erasePartition != null) {
            constants = ValTokens.deriveEraseTokenConstants(req.secret, device, erasePartition, alphabetName)
        } else if (req.serialno != null) {
            constants = ValTokens.deriveRuntimeSerialCompactConstants(req.secret, req.serialno)
            deviceOut = req.serialno.trim()
            deviceKind = "runtime-serial"
        } else {
            constants = ValTokens.deriveTokenConstants(req.secret, device, alphabetName)
        }
        val alphabet = ValTokens.tokenBytesForAlphabet(alphabetName).map { it.toChar() }.joinToString("")
        val rng = if (req.seed != null) PythonRandom(req.seed) else null
        val keys = uniqueKeys(constants, req.count, alphabet, rng)
        val mode = if (erasePartition != null) "erase-token" else "key-token"
        return KeygenResult(mode, req.secret, deviceOut, deviceKind, erasePartition, alphabetName, constants, keys)
    }
}
