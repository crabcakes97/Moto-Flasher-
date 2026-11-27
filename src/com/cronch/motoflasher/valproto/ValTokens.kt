// Token derivation (SHA-256) + code-cave stub builders.
// Port of lk_patch_partition.py derive_*/build_* stub functions.
package com.cronch.motoflasher.valproto

import java.security.MessageDigest

object ValTokens {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun tokenBytesForAlphabet(alphabet: String): List<Int> = when (alphabet) {
        "printable" -> ValPatchCore.PRINTABLE_TOKEN_BYTES
        "alnum" -> ValPatchCore.ALNUM_TOKEN_BYTES
        else -> throw IllegalArgumentException("alphabet must be 'alnum' or 'printable'")
    }

    fun tokenXorValuesForAlphabet(alphabet: String): List<Int> {
        val tokenBytes = tokenBytesForAlphabet(alphabet)
        val set = sortedSetOf<Int>()
        for (left in tokenBytes) for (right in tokenBytes) set.add(left xor right)
        return set.toList()
    }

    fun deriveTokenConstants(secret: String, device: String = "", alphabet: String = "alnum"): ByteArray {
        val secretRaw = secret.toByteArray(Charsets.UTF_8)
        val deviceRaw = device.toByteArray(Charsets.UTF_8)
        require(secretRaw.isNotEmpty()) { "secret must not be empty" }
        val mask = sha256("lk-token-mask\u0000".toByteArray(), secretRaw, byteArrayOf(0), deviceRaw).copyOfRange(0, 10)
        val target = byteArrayOf('M'.code.toByte()) + sha256("lk-token-target\u0000".toByteArray(), deviceRaw).copyOfRange(0, 9)
        val xored = ByteArray(10) { i -> (target[i].toInt() xor mask[i].toInt()).toByte() }
        val raw = sha256("lk-token-constants\u0000".toByteArray(), xored, byteArrayOf(0), secretRaw, byteArrayOf(0), deviceRaw)
        val xorValues = tokenXorValuesForAlphabet(alphabet)
        return ByteArray(10) { i -> xorValues[(raw[i].toInt() and 0xFF) % xorValues.size].toByte() }
    }

    fun normalizeSerialno(value: String): String {
        val text = value.trim()
        require(text.isNotEmpty()) { "serialno must not be empty" }
        val raw = text.toByteArray(Charsets.US_ASCII)
        require(text.all { it.code in 1..127 }) { "serialno must use ASCII" }
        require(raw.size > ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS.max()) {
            "serialno too short; need at least ${ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS.max() + 1} characters"
        }
        require(raw.all { it.toInt() != 0 && it.toInt() < 0x80 }) { "serialno must contain only ASCII 1..127" }
        return text
    }

    fun deriveRuntimeSerialCompactMasks(secret: String): ByteArray {
        val secretRaw = secret.toByteArray(Charsets.UTF_8)
        require(secretRaw.isNotEmpty()) { "secret must not be empty" }
        val raw = sha256("lk-token-runtime-serial-compact\u0000".toByteArray(), secretRaw)
        return ByteArray(ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS.size) { i -> (raw[i].toInt() and 0x7F).toByte() }
    }

    fun deriveRuntimeSerialCompactConstants(secret: String, serialno: String): ByteArray {
        val serialRaw = normalizeSerialno(serialno).toByteArray(Charsets.US_ASCII)
        val masks = deriveRuntimeSerialCompactMasks(secret)
        return ByteArray(ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS.size) { index ->
            val position = ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS[index]
            ((serialRaw[position].toInt() xor masks[index].toInt()) and 0x7F).toByte()
        }
    }

    fun deriveEraseTokenConstants(secret: String, device: String, partition: String, alphabet: String = "alnum"): ByteArray {
        val secretRaw = secret.toByteArray(Charsets.UTF_8)
        val deviceRaw = device.toByteArray(Charsets.UTF_8)
        val partitionRaw = partition.toByteArray(Charsets.US_ASCII)
        require(secretRaw.isNotEmpty()) { "secret must not be empty" }
        require(partitionRaw.isNotEmpty()) { "partition must not be empty" }
        val raw = sha256(
            "lk-erase-token-constants\u0000".toByteArray(), secretRaw, byteArrayOf(0),
            deviceRaw, byteArrayOf(0), partitionRaw,
        )
        val xorValues = tokenXorValuesForAlphabet(alphabet)
        return ByteArray(10) { i -> xorValues[(raw[i].toInt() and 0xFF) % xorValues.size].toByte() }
    }

    fun findZeroCave(data: ByteArray, minSize: Int, startOffset: Int): Int {
        var index = maxOf(0, startOffset)
        while (index < data.size) {
            if (data[index] != 0.toByte()) { index++; continue }
            val runStart = index
            while (index < data.size && data[index] == 0.toByte()) index++
            val runEnd = index
            val aligned = (runStart + 3) and -4
            if (aligned + minSize <= runEnd) return aligned
        }
        throw IllegalArgumentException("no $minSize-byte NUL code cave found after 0x${startOffset.toString(16)}")
    }

    fun requireZeroCave(data: ByteArray, caveOffset: Int, size: Int) {
        require(caveOffset >= 0 && caveOffset + size <= data.size) { "code cave out of file: 0x${caveOffset.toString(16)}" }
        require(caveOffset % 4 == 0) { "code cave not 4-byte aligned: 0x${caveOffset.toString(16)}" }
        val current = data.copyOfRange(caveOffset, caveOffset + size)
        require(current.all { it == 0.toByte() }) { "code cave 0x${caveOffset.toString(16)} is not empty for $size bytes" }
    }

    fun keyValidatorThumbReturnValues(validator: JsonObj): Pair<Int, Int> {
        for (edge in validator.arr("failure_edges").filterIsInstance<JsonObj>()) {
            val branch = (edge.str("branch") ?: "").lowercase()
            if (branch == "cbnz") return 1 to 0
            if (branch == "cbz") return 0 to 1
        }
        return 1 to 0
    }

    fun adjustThumbSerialFuncOffset(data: ByteArray, serialFuncOffset: Int): Int {
        if (serialFuncOffset >= 4 && serialFuncOffset + 2 <= data.size) {
            val prev0 = ValBytes.u16At(data, serialFuncOffset - 4)
            val prev1 = ValBytes.u16At(data, serialFuncOffset - 2)
            val here = ValBytes.u16At(data, serialFuncOffset)
            val isLdrR1Pc = (prev0 and 0xF800) == 0x4800 && ((prev0 shr 8) and 7) == 1
            val isMovR2R0 = prev1 == 0x4602
            val isPush = (here and 0xFE00) == 0xB400
            if (isLdrR1Pc && isMovR2R0 && isPush) return serialFuncOffset - 4
        }
        return serialFuncOffset
    }
}

/** Label/relocation assembler used by every stub builder below.
 *  pc() is the file offset the next emitted byte will occupy.
 */
class StubAsm(val base: Int) {
    private val code = mutableListOf<Byte>()
    private val labels = mutableMapOf<String, Int>()
    private val relocs = mutableListOf<Triple<Int, String, String>>()

    fun pc(): Int = base + code.size
    fun size(): Int = code.size
    fun emit(raw: ByteArray) { raw.forEach { code.add(it) } }
    fun mark(label: String) { labels[label] = pc() }
    fun branchLater(kind: String, label: String) {
        relocs.add(Triple(code.size, kind, label))
        emit(ByteArray(4))
    }

    fun branchLater6(kind: String, label: String) {
        relocs.add(Triple(code.size, kind, label))
        emit(ByteArray(6))
    }

    fun bneLater16(label: String) {
        relocs.add(Triple(code.size, "bne16", label))
        emit(ByteArray(2))
    }

    fun beqLater16(label: String) {
        relocs.add(Triple(code.size, "beq16", label))
        emit(ByteArray(2))
    }

    fun bytes(): ByteArray = code.toByteArray()

    /** Resolve 4-byte AArch64 relocs. */
    fun resolveA64() {
        val out = bytes()
        for ((pos, kind, label) in relocs) {
            val source = base + pos
            val target = labels[label] ?: throw IllegalArgumentException("reloc without label: $label")
            val replacement: ByteArray = when (kind) {
                "cbz_x0" -> ValEncoders.cbzX(source.toLong(), target.toLong(), 0)
                "cbz_w11" -> ValEncoders.cbzW(source.toLong(), target.toLong(), 11)
                "b.ne" -> ValEncoders.branchCond(source.toLong(), target.toLong(), 1)
                "b.eq" -> ValEncoders.branchCond(source.toLong(), target.toLong(), 0)
                "b.hi" -> ValEncoders.branchCond(source.toLong(), target.toLong(), 8)
                "b" -> ValEncoders.branchImm26(source.toLong(), target.toLong())
                else -> throw IllegalArgumentException("unknown reloc: $kind")
            }
            replacement.copyInto(out, pos)
        }
        code.clear()
        out.forEach { code.add(it) }
    }

    /** Resolve 2-byte Thumb16 relocs. */
    fun resolveThumb16() {
        val out = bytes()
        for ((pos, kind, label) in relocs) {
            val source = base + pos
            val target = labels[label] ?: throw IllegalArgumentException("reloc without label: $label")
            val replacement: ByteArray = when (kind) {
                "bne16" -> ValEncoders.thumbBCond16(source, target, 1)
                "beq16" -> ValEncoders.thumbBCond16(source, target, 0)
                else -> throw IllegalArgumentException("unknown Thumb reloc: $kind")
            }
            replacement.copyInto(out, pos)
        }
        code.clear()
        out.forEach { code.add(it) }
    }

    /** Resolve the 6-byte Thumb long-branch relocs of the runtime-serial stub. */
    fun resolveThumbLong() {
        val out = bytes()
        for ((pos, kind, label) in relocs) {
            val source = base + pos
            val target = labels[label] ?: throw IllegalArgumentException("reloc without label: $label")
            val inverseCond = when (kind) {
                "beq" -> 1
                "bne" -> 0
                "bhi" -> 9
                else -> throw IllegalArgumentException("unknown Thumb reloc: $kind")
            }
            val replacement = ValEncoders.thumbBCond16(source, source + 6, inverseCond) +
                ValEncoders.thumbBW(source + 2, target)
            replacement.copyInto(out, pos)
        }
        code.clear()
        out.forEach { code.add(it) }
    }
}

object ValStubs {
    private val MOV_W0_ONE = ValPatchCore.MOV_W0_ONE
    private val RET = ValPatchCore.RET

    fun buildKeySignatureStub(entryOffset: Int, caveOffset: Int, originalInstr: ByteArray, signature: ByteArray): ByteArray {
        require(originalInstr.size == 4) { "original_instr must be 4 bytes" }
        require(signature.size == 20) { "signature must be 20 bytes" }
        val a = StubAsm(caveOffset)
        a.branchLater("cbz_x0", "original")
        a.emit(ValEncoders.ldrUnsigned(9, 0, 0, 64))
        a.emit(ValEncoders.movImm64(10, signature.copyOfRange(0, 8).foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * i)) }))
        a.emit(ValEncoders.cmpReg(9, 10, 64))
        a.branchLater("b.ne", "original")
        a.emit(ValEncoders.ldrUnsigned(9, 0, 8, 64))
        a.emit(ValEncoders.movImm64(10, signature.copyOfRange(8, 16).foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * i)) }))
        a.emit(ValEncoders.cmpReg(9, 10, 64))
        a.branchLater("b.ne", "original")
        a.emit(ValEncoders.ldrUnsigned(9, 0, 16, 32))
        a.emit(ValEncoders.movImm32(10, signature.copyOfRange(16, 20).foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * i)) }))
        a.emit(ValEncoders.cmpReg(9, 10, 32))
        a.branchLater("b.ne", "original")
        a.emit(MOV_W0_ONE)
        a.emit(RET)
        a.mark("original")
        a.emit(originalInstr)
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), (entryOffset + 4).toLong()))
        a.resolveA64()
        return a.bytes()
    }

    fun buildKeyTokenStub(entryOffset: Int, caveOffset: Int, originalInstr: ByteArray, constants: ByteArray): ByteArray {
        require(originalInstr.size == 4) { "original_instr must be 4 bytes" }
        require(constants.size == 10) { "constants must be 10 bytes" }
        val a = StubAsm(caveOffset)
        a.branchLater("cbz_x0", "original")
        for ((index, expected) in constants.withIndex()) {
            a.emit(ValEncoders.ldrbUnsigned(9, 0, index))
            a.emit(ValEncoders.ldrbUnsigned(10, 0, 19 - index))
            a.emit(ValEncoders.eorReg32(9, 9, 10))
            a.emit(ValEncoders.cmpImm32(9, expected.toInt() and 0xFF))
            a.branchLater("b.ne", "original")
        }
        a.emit(MOV_W0_ONE)
        a.emit(RET)
        a.mark("original")
        a.emit(originalInstr)
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), (entryOffset + 4).toLong()))
        a.resolveA64()
        return a.bytes()
    }

    fun buildKeyRuntimeSerialCompactStub(entryOffset: Int, serialFuncOffset: Int, masks: ByteArray, debugStop: String? = null): ByteArray {
        require(masks.size == ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS.size) { "masks must be 10 bytes" }
        val a = StubAsm(entryOffset)
        fun emitZeroReturn() {
            a.emit(ValEncoders.movReg32(0, 31))
            a.emit(RET)
        }
        fun emitRestore() {
            a.emit(ValEncoders.ldrUnsigned(21, 31, 0x58, 64))
            a.emit(ValEncoders.ldrUnsigned(19, 31, 0x60, 64))
            a.emit(ValEncoders.ldrUnsigned(30, 31, 0x68, 64))
            a.emit(ValEncoders.addImm64(31, 31, 0x70))
        }
        fun emitRestoreZeroReturn() { emitRestore(); emitZeroReturn() }
        a.branchLater("cbz_x0", "fail_direct")
        a.emit(ValEncoders.subImm64(31, 31, 0x70))
        a.emit(ValEncoders.strUnsigned(30, 31, 0x68, 64))
        a.emit(ValEncoders.strUnsigned(19, 31, 0x60, 64))
        a.emit(ValEncoders.strUnsigned(21, 31, 0x58, 64))
        a.emit(ValEncoders.movReg64(19, 0))
        a.emit(ValEncoders.strUnsigned(31, 31, 0x10, 32))
        a.emit(ValEncoders.strUnsigned(31, 31, 0x14, 32))
        a.emit(ValEncoders.strUnsigned(31, 31, 0x18, 32))
        if (debugStop == "before_serial") emitRestoreZeroReturn()
        a.emit(ValEncoders.addImm64(0, 31, 0x10))
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), serialFuncOffset.toLong(), 0x94000000L))
        if (debugStop == "after_serial") emitRestoreZeroReturn()
        a.emit(ValEncoders.addImm64(21, 31, 0x10))
        for ((index, serialPos) in ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS.withIndex()) {
            a.emit(ValEncoders.ldrbUnsigned(11, 21, serialPos))
            a.branchLater("cbz_w11", "fail")
            a.emit(ValEncoders.cmpImm32(11, 0x7F))
            a.branchLater("b.hi", "fail")
            a.emit(ValEncoders.movImm32(12, masks[index].toLong() and 0xFF))
            a.emit(ValEncoders.eorReg32(11, 11, 12))
            a.emit(ValEncoders.ldrbUnsigned(9, 19, index))
            a.emit(ValEncoders.ldrbUnsigned(10, 19, 19 - index))
            a.emit(ValEncoders.eorReg32(9, 9, 10))
            a.emit(ValEncoders.cmpReg(9, 11, 32))
            a.branchLater("b.ne", "fail")
        }
        if (debugStop == "token_ok") emitRestoreZeroReturn()
        emitRestore()
        a.emit(MOV_W0_ONE)
        a.emit(RET)
        a.mark("fail")
        if (debugStop == "token_fail") emitRestoreZeroReturn() else emitRestoreZeroReturn()
        a.mark("fail_direct")
        emitZeroReturn()
        a.resolveA64()
        return a.bytes()
    }

    fun buildThumbKeyRuntimeSerialCompactStub(entryOffset: Int, serialFuncOffset: Int, masks: ByteArray, successValue: Int, failValue: Int): ByteArray {
        require(masks.size == ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS.size) { "masks must be 10 bytes" }
        require(successValue in 0..1 && failValue in 0..1) { "success/fail Thumb deben ser booleanos" }
        val a = StubAsm(entryOffset)
        a.emit(ValEncoders.thumbCmpImm(0, 0))
        a.branchLater6("beq", "fail_direct")
        a.emit(ValEncoders.thumbPush(listOf(4, 5, 6, 14)))
        a.emit(ValEncoders.thumbSubSp(0x20))
        a.emit(ValEncoders.thumbMovReg(4, 0))
        a.emit(ValEncoders.thumbMovsImm(1, 0))
        a.emit(ValEncoders.thumbStrSpImm(1, 0))
        a.emit(ValEncoders.thumbStrSpImm(1, 4))
        a.emit(ValEncoders.thumbStrSpImm(1, 8))
        a.emit(ValEncoders.thumbStrSpImm(1, 12))
        a.emit(ValEncoders.thumbStrSpImm(1, 16))
        a.emit(ValEncoders.thumbStrSpImm(1, 20))
        a.emit(ValEncoders.thumbStrSpImm(1, 24))
        a.emit(ValEncoders.thumbStrSpImm(1, 28))
        a.emit(ValEncoders.thumbAddSpToReg(0, 0))
        a.emit(ValEncoders.thumbBl(a.pc(), serialFuncOffset))
        a.emit(ValEncoders.thumbAddSpToReg(5, 0))
        for ((index, serialPos) in ValPatchCore.RUNTIME_SERIAL_TOKEN_POSITIONS.withIndex()) {
            a.emit(ValEncoders.thumbLdrbImm(1, 5, serialPos))
            a.emit(ValEncoders.thumbCmpImm(1, 0))
            a.branchLater6("beq", "fail")
            a.emit(ValEncoders.thumbCmpImm(1, 0x7F))
            a.branchLater6("bhi", "fail")
            a.emit(ValEncoders.thumbMovsImm(6, masks[index].toInt() and 0xFF))
            a.emit(ValEncoders.thumbEors(1, 6))
            a.emit(ValEncoders.thumbLdrbImm(2, 4, index))
            a.emit(ValEncoders.thumbLdrbImm(3, 4, 19 - index))
            a.emit(ValEncoders.thumbEors(2, 3))
            a.emit(ValEncoders.thumbCmpReg(2, 1))
            a.branchLater6("bne", "fail")
        }
        a.mark("success")
        a.emit(ValEncoders.thumbAddSp(0x20))
        a.emit(ValEncoders.thumbMovsImm(0, successValue))
        a.emit(ValEncoders.thumbPop(listOf(4, 5, 6, 15)))
        a.mark("fail")
        a.emit(ValEncoders.thumbAddSp(0x20))
        a.emit(ValEncoders.thumbMovsImm(0, failValue))
        a.emit(ValEncoders.thumbPop(listOf(4, 5, 6, 15)))
        a.mark("fail_direct")
        a.emit(ValEncoders.thumbMovsImm(0, failValue))
        a.emit(ValEncoders.thumbBxLr())
        a.resolveThumbLong()
        return a.bytes()
    }

    fun emitByteCompare(a: StubAsm, rn: Int, offset: Int, expected: Int, failureLabel: String) {
        a.emit(ValEncoders.ldrbUnsigned(9, rn, offset))
        a.emit(ValEncoders.cmpImm32(9, expected))
        a.branchLater("b.ne", failureLabel)
    }

    fun buildEraseTokenPreStub(hookOffset: Int, caveOffset: Int, originalInstr: ByteArray, partition: ByteArray, constants: ByteArray, marker: Int = 0xA5): ByteArray {
        require(originalInstr.size == 4) { "original_instr must be 4 bytes" }
        require(constants.size == 10) { "constants must be 10 bytes" }
        return buildEraseTokenPreStubReg(hookOffset, caveOffset, originalInstr, partition, constants, 19, marker)
    }

    private fun buildEraseTokenPreStubReg(hookOffset: Int, caveOffset: Int, originalInstr: ByteArray, partition: ByteArray, constants: ByteArray, pointerReg: Int, marker: Int): ByteArray {
        require(originalInstr.size == 4) { "original_instr must be 4 bytes" }
        require(constants.size == 10) { "constants must be 10 bytes" }
        val a = StubAsm(caveOffset)
        for ((index, expected) in partition.withIndex()) emitByteCompare(a, pointerReg, index, expected.toInt() and 0xFF, "original")
        emitByteCompare(a, pointerReg, partition.size, '_'.code, "original")
        emitByteCompare(a, pointerReg, partition.size + 1, '_'.code, "original")
        val tokenOffset = partition.size + 2
        for ((index, expected) in constants.withIndex()) {
            a.emit(ValEncoders.ldrbUnsigned(9, pointerReg, tokenOffset + index))
            a.emit(ValEncoders.ldrbUnsigned(10, pointerReg, tokenOffset + 19 - index))
            a.emit(ValEncoders.eorReg32(9, 9, 10))
            a.emit(ValEncoders.cmpImm32(9, expected.toInt() and 0xFF))
            a.branchLater("b.ne", "original")
        }
        emitByteCompare(a, pointerReg, tokenOffset + 20, 0, "original")
        a.emit(ValEncoders.strbUnsigned(31, pointerReg, partition.size))
        a.emit(ValEncoders.movImm32(9, marker.toLong()))
        a.emit(ValEncoders.strbUnsigned(9, pointerReg, partition.size + 1))
        a.mark("original")
        a.emit(ValEncoders.relocateAarch64Branch(originalInstr, hookOffset.toLong(), a.pc().toLong()))
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), (hookOffset + 4).toLong()))
        a.resolveA64()
        return a.bytes()
    }

    fun buildEraseTokenPreStubFromPointer(hookOffset: Int, caveOffset: Int, originalInstr: ByteArray, partition: ByteArray, constants: ByteArray, pointerReg: Int, marker: Int = 0xA5): ByteArray =
        buildEraseTokenPreStubReg(hookOffset, caveOffset, originalInstr, partition, constants, pointerReg, marker)

    fun buildEraseTokenGateStub(hookOffset: Int, caveOffset: Int, allowedTarget: Int, deniedTarget: Int, partition: ByteArray, marker: Int = 0xA5): ByteArray {
        val a = StubAsm(caveOffset)
        a.emit(ValEncoders.cmpImm32(0, 0))
        a.branchLater("b.ne", "allow")
        for ((index, expected) in partition.withIndex()) emitByteCompare(a, 19, index, expected.toInt() and 0xFF, "deny")
        emitByteCompare(a, 19, partition.size, 0, "deny")
        emitByteCompare(a, 19, partition.size + 1, marker, "deny")
        a.branchLater("b", "allow")
        a.mark("deny")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), deniedTarget.toLong()))
        a.mark("allow")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), allowedTarget.toLong()))
        a.resolveA64()
        require(hookOffset % 4 == 0) { "hook gate not aligned: 0x${hookOffset.toString(16)}" }
        return a.bytes()
    }

    fun buildEraseTokenGateStubFromArgument(hookOffset: Int, caveOffset: Int, allowedTarget: Int, deniedTarget: Int, partition: ByteArray, argumentReg: Int, tokenAllowedTarget: Int? = null, tokenDeniedTarget: Int? = null, marker: Int = 0xA5): ByteArray {
        val tokAllow = tokenAllowedTarget ?: allowedTarget
        val tokDeny = tokenDeniedTarget ?: deniedTarget
        val a = StubAsm(caveOffset)
        a.emit(ValEncoders.cmpImm32(0, 0))
        a.branchLater("b.ne", "original_allow")
        a.emit(ValEncoders.addImm64(11, argumentReg, 1))
        for ((index, expected) in partition.withIndex()) emitByteCompare(a, 11, index, expected.toInt() and 0xFF, "deny")
        a.emit(ValEncoders.ldrbUnsigned(9, 11, partition.size))
        a.emit(ValEncoders.cmpImm32(9, 0))
        a.branchLater("b.ne", "target_name_invalid")
        a.emit(ValEncoders.ldrbUnsigned(9, 11, partition.size + 1))
        a.emit(ValEncoders.cmpImm32(9, marker))
        a.branchLater("b.ne", "target_name_denied")
        a.branchLater("b", "token_allow")
        a.mark("target_name_invalid")
        a.emit(ValEncoders.cmpImm32(9, '_'.code))
        a.branchLater("b.ne", "deny")
        a.mark("target_name_denied")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), tokDeny.toLong()))
        a.mark("deny")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), deniedTarget.toLong()))
        a.mark("token_allow")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), tokAllow.toLong()))
        a.mark("original_allow")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), allowedTarget.toLong()))
        a.resolveA64()
        require(hookOffset % 4 == 0) { "hook gate not aligned: 0x${hookOffset.toString(16)}" }
        return a.bytes()
    }

    fun buildEraseTokenGateStubFromPointer(hookOffset: Int, caveOffset: Int, allowedTarget: Int, deniedTarget: Int, partition: ByteArray, pointerReg: Int, tokenAllowedTarget: Int? = null, tokenDeniedTarget: Int? = null, marker: Int = 0xA5): ByteArray {
        val tokAllow = tokenAllowedTarget ?: allowedTarget
        val tokDeny = tokenDeniedTarget ?: deniedTarget
        val a = StubAsm(caveOffset)
        a.emit(ValEncoders.cmpImm32(0, 0))
        a.branchLater("b.ne", "original_allow")
        for ((index, expected) in partition.withIndex()) emitByteCompare(a, pointerReg, index, expected.toInt() and 0xFF, "deny")
        a.emit(ValEncoders.ldrbUnsigned(9, pointerReg, partition.size))
        a.emit(ValEncoders.cmpImm32(9, 0))
        a.branchLater("b.ne", "target_name_invalid")
        a.emit(ValEncoders.ldrbUnsigned(9, pointerReg, partition.size + 1))
        a.emit(ValEncoders.cmpImm32(9, marker))
        a.branchLater("b.ne", "target_name_denied")
        a.branchLater("b", "token_allow")
        a.mark("target_name_invalid")
        a.emit(ValEncoders.cmpImm32(9, '_'.code))
        a.branchLater("b.ne", "deny")
        a.mark("target_name_denied")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), tokDeny.toLong()))
        a.mark("deny")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), deniedTarget.toLong()))
        a.mark("token_allow")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), tokAllow.toLong()))
        a.mark("original_allow")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), allowedTarget.toLong()))
        a.resolveA64()
        require(hookOffset % 4 == 0) { "hook gate not aligned: 0x${hookOffset.toString(16)}" }
        return a.bytes()
    }

    fun buildEraseCommandGateStubFromPointer(hookOffset: Int, caveOffset: Int, allowedTarget: Int, deniedTarget: Int, partition: ByteArray, pointerReg: Int, pointerFromArgument: Boolean = false, marker: Int = 0xA5): ByteArray {
        val a = StubAsm(caveOffset)
        var activePointerReg = pointerReg
        if (pointerFromArgument) {
            activePointerReg = 11
            a.emit(ValEncoders.addImm64(activePointerReg, pointerReg, 1))
        }
        for ((index, expected) in partition.withIndex()) emitByteCompare(a, activePointerReg, index, expected.toInt() and 0xFF, "original_decision")
        a.emit(ValEncoders.ldrbUnsigned(9, activePointerReg, partition.size))
        a.emit(ValEncoders.cmpImm32(9, 0))
        a.branchLater("b.ne", "target_name_invalid")
        a.emit(ValEncoders.ldrbUnsigned(9, activePointerReg, partition.size + 1))
        a.emit(ValEncoders.cmpImm32(9, marker))
        a.branchLater("b.eq", "token_allow")
        a.branchLater("b", "original_decision")
        a.mark("target_name_invalid")
        a.emit(ValEncoders.cmpImm32(9, '_'.code))
        a.branchLater("b.ne", "original_decision")
        a.branchLater("b", "token_deny")
        a.mark("token_deny")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), deniedTarget.toLong()))
        a.mark("token_allow")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), allowedTarget.toLong()))
        a.mark("original_decision")
        a.emit(ValEncoders.cmnImm32(0, 1))
        a.branchLater("b.eq", "original_allow")
        a.branchLater("b", "original_deny")
        a.mark("original_allow")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), allowedTarget.toLong()))
        a.mark("original_deny")
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), deniedTarget.toLong()))
        a.resolveA64()
        require(hookOffset % 4 == 0) { "hook gate not aligned: 0x${hookOffset.toString(16)}" }
        return a.bytes()
    }

    fun buildEraseCommandLateNameStub(hookOffset: Int, caveOffset: Int, originalInstr: ByteArray, partition: ByteArray, constants: ByteArray, commandReg: Int = 21): ByteArray {
        require(originalInstr.size == 4) { "original_instr must be 4 bytes" }
        require(constants.size == 10) { "constants must be 10 bytes" }
        val a = StubAsm(caveOffset)
        a.emit(ValEncoders.ldrUnsigned(11, commandReg, 0x10, 64))
        a.emit(ValEncoders.addImm64(11, 11, 1))
        for ((index, expected) in partition.withIndex()) emitByteCompare(a, 11, index, expected.toInt() and 0xFF, "original")
        emitByteCompare(a, 11, partition.size, '_'.code, "original")
        emitByteCompare(a, 11, partition.size + 1, '_'.code, "original")
        val tokenOffset = partition.size + 2
        for ((index, expected) in constants.withIndex()) {
            a.emit(ValEncoders.ldrbUnsigned(9, 11, tokenOffset + index))
            a.emit(ValEncoders.ldrbUnsigned(10, 11, tokenOffset + 19 - index))
            a.emit(ValEncoders.eorReg32(9, 9, 10))
            a.emit(ValEncoders.cmpImm32(9, expected.toInt() and 0xFF))
            a.branchLater("b.ne", "original")
        }
        emitByteCompare(a, 11, tokenOffset + 20, 0, "original")
        a.emit(ValEncoders.strbUnsigned(31, 11, partition.size))
        a.mark("original")
        a.emit(ValEncoders.relocateAarch64Branch(originalInstr, hookOffset.toLong(), a.pc().toLong()))
        a.emit(ValEncoders.branchImm26(a.pc().toLong(), (hookOffset + 4).toLong()))
        a.resolveA64()
        return a.bytes()
    }

    fun buildThumbEraseTokenPreStub(hookOffset: Int, caveOffset: Int, partition: ByteArray, constants: ByteArray, marker: Int = 0xA5): ByteArray {
        require(constants.size == 10) { "constants must be 10 bytes" }
        require(partition.size + 23 <= 31) { "partition too long for token Thumb stub" }
        val a = StubAsm(caveOffset)
        a.emit(ValEncoders.thumbLdrImm(4, 4, 0x0C))
        a.emit(ValEncoders.thumbMovsImm(2, 0x48))
        a.emit(ValEncoders.thumbAddImm3(0, 4, 1))
        for ((index, expected) in partition.withIndex()) {
            a.emit(ValEncoders.thumbLdrbImm(3, 0, index))
            a.emit(ValEncoders.thumbCmpImm(3, expected.toInt() and 0xFF))
            a.bneLater16("done")
        }
        a.emit(ValEncoders.thumbLdrbImm(3, 0, partition.size))
        a.emit(ValEncoders.thumbCmpImm(3, '_'.code))
        a.bneLater16("done")
        a.emit(ValEncoders.thumbLdrbImm(3, 0, partition.size + 1))
        a.emit(ValEncoders.thumbCmpImm(3, '_'.code))
        a.bneLater16("done")
        val tokenOffset = partition.size + 2
        for ((index, expected) in constants.withIndex()) {
            a.emit(ValEncoders.thumbLdrbImm(2, 0, tokenOffset + index))
            a.emit(ValEncoders.thumbLdrbImm(3, 0, tokenOffset + 19 - index))
            a.emit(ValEncoders.thumbEors(2, 3))
            a.emit(ValEncoders.thumbCmpImm(2, expected.toInt() and 0xFF))
            a.bneLater16("done")
        }
        a.emit(ValEncoders.thumbLdrbImm(3, 0, tokenOffset + 20))
        a.emit(ValEncoders.thumbCmpImm(3, 0))
        a.bneLater16("done")
        a.emit(ValEncoders.thumbMovsImm(3, 0))
        a.emit(ValEncoders.thumbStrbImm(3, 0, partition.size))
        a.emit(ValEncoders.thumbMovsImm(3, marker))
        a.emit(ValEncoders.thumbStrbImm(3, 0, partition.size + 1))
        a.mark("done")
        a.emit(ValEncoders.thumbMovsImm(2, 0x48))
        a.emit(ValEncoders.thumbBW(a.pc(), hookOffset + 4))
        a.resolveThumb16()
        return a.bytes()
    }

    fun buildThumbKeyTokenStub(entryOffset: Int, caveOffset: Int, originalInstr: ByteArray, constants: ByteArray): ByteArray {
        require(originalInstr.size == 4) { "original_instr must be 4 bytes" }
        require(constants.size == 10) { "constants must be 10 bytes" }
        val a = StubAsm(caveOffset)
        a.emit(ValEncoders.thumbCmpImm(0, 0))
        a.beqLater16("original")
        for ((index, expected) in constants.withIndex()) {
            a.emit(ValEncoders.thumbLdrbImm(2, 0, index))
            a.emit(ValEncoders.thumbLdrbImm(3, 0, 19 - index))
            a.emit(ValEncoders.thumbEors(2, 3))
            a.emit(ValEncoders.thumbCmpImm(2, expected.toInt() and 0xFF))
            a.bneLater16("original")
        }
        a.emit(ValEncoders.thumbMovsImm(0, 1))
        a.emit(ValEncoders.u16(0x4770)) // bx lr
        a.mark("original")
        a.emit(originalInstr)
        a.emit(ValEncoders.thumbBW(a.pc(), entryOffset + 4))
        a.resolveThumb16()
        return a.bytes()
    }

    fun buildThumbEraseTokenGateStub(hookOffset: Int, caveOffset: Int, allowedTarget: Int, deniedTarget: Int, partition: ByteArray, marker: Int = 0xA5): ByteArray {
        require(partition.size + 1 <= 31) { "partition too long for gate Thumb stub" }
        val a = StubAsm(caveOffset)
        a.emit(ValEncoders.thumbCmpImm(0, 0))
        a.bneLater16("allow")
        for ((index, expected) in partition.withIndex()) {
            a.emit(ValEncoders.thumbLdrbImm(3, 4, index))
            a.emit(ValEncoders.thumbCmpImm(3, expected.toInt() and 0xFF))
            a.bneLater16("deny")
        }
        a.emit(ValEncoders.thumbLdrbImm(3, 4, partition.size))
        a.emit(ValEncoders.thumbCmpImm(3, 0))
        a.bneLater16("deny")
        a.emit(ValEncoders.thumbLdrbImm(3, 4, partition.size + 1))
        a.emit(ValEncoders.thumbCmpImm(3, marker))
        a.bneLater16("deny")
        a.mark("allow")
        a.emit(ValEncoders.u16(0x4620)) // mov r0, r4; original instruction overwritten after the hook.
        a.emit(ValEncoders.thumbBW(a.pc(), allowedTarget))
        a.mark("deny")
        a.emit(ValEncoders.thumbBW(a.pc(), deniedTarget))
        a.resolveThumb16()
        return a.bytes()
    }
}
