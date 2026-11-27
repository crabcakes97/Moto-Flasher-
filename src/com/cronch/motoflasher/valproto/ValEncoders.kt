// AArch64 + ARM32-Thumb instruction encoders/decoders.
// Straight port of lk_patch_partition.py encoder/decoder helpers.
package com.cronch.motoflasher.valproto

object ValEncoders {
    fun u32(value: Long): ByteArray = ValBytes.packU32(value)
    fun u16(value: Int): ByteArray = ValBytes.packU16(value)

    fun encMovzW(rd: Int, imm16: Int): ByteArray {
        require(imm16 in 0..0xFFFF) { "movz imm out of range: 0x${imm16.toString(16)}" }
        return u32(0x52800000L or (imm16.toLong() shl 5) or rd.toLong())
    }

    /** Encode B/BL with 26-bit immediate. opcode 0x14000000 = B, 0x94000000 = BL. */
    fun branchImm26(source: Long, target: Long, opcode: Long = 0x14000000L): ByteArray {
        val delta = target - source
        require(delta % 4 == 0L) { "branch not aligned: 0x${uHex(source)} -> 0x${uHex(target)}" }
        val imm = delta / 4
        require(imm >= -(1L shl 25) && imm < (1L shl 25)) { "branch fuera de rango: 0x${uHex(source)} -> 0x${uHex(target)}" }
        return u32(opcode or (imm and 0x03FFFFFFL))
    }

    fun relocateAarch64Branch(originalInstr: ByteArray, originalOffset: Long, newOffset: Long): ByteArray {
        require(originalInstr.size == 4) { "original_instr must be 4 bytes" }
        val word = ValBytes.u32At(originalInstr, 0)
        val opcode = word and 0xFC000000L
        if (opcode != 0x14000000L && opcode != 0x94000000L) return originalInstr.copyOf()
        var imm = word and 0x03FFFFFFL
        if (imm and 0x02000000L != 0L) imm -= 0x04000000L
        val target = originalOffset + imm * 4
        return branchImm26(newOffset, target, opcode)
    }

    fun encBl(srcVa: Long, dstVa: Long): ByteArray {
        val delta = dstVa - srcVa
        require(delta % 4 == 0L) { "bl not aligned: 0x${uHex(srcVa)} -> 0x${uHex(dstVa)}" }
        val imm = delta / 4
        require(imm >= -(1L shl 25) && imm < (1L shl 25)) { "bl fuera de rango: 0x${uHex(srcVa)} -> 0x${uHex(dstVa)}" }
        return u32(0x94000000L or (imm and 0x3FFFFFFL))
    }

    fun encB(srcVa: Long, dstVa: Long): ByteArray {
        val delta = dstVa - srcVa
        require(delta % 4 == 0L) { "b not aligned: 0x${uHex(srcVa)} -> 0x${uHex(dstVa)}" }
        val imm = delta / 4
        require(imm >= -(1L shl 25) && imm < (1L shl 25)) { "b fuera de rango: 0x${uHex(srcVa)} -> 0x${uHex(dstVa)}" }
        return u32(0x14000000L or (imm and 0x3FFFFFFL))
    }

    /** Encode ADRP+ADD rd, dst. Returns (adrp, add). */
    fun encAdrpAdd(rd: Int, srcVa: Long, dstVa: Long): Pair<ByteArray, ByteArray> {
        val pageDiff = ((dstVa and -0x1000L) - (srcVa and -0x1000L)) / 0x1000L
        require(pageDiff >= -(1L shl 20) && pageDiff < (1L shl 20)) { "adrp fuera de rango: 0x${uHex(srcVa)} -> 0x${uHex(dstVa)}" }
        val imm = pageDiff and 0x1FFFFFL
        val adrp = u32(0x90000000L or ((imm and 3L) shl 29) or (((imm shr 2) and 0x7FFFFL) shl 5) or rd.toLong())
        val lo12 = dstVa and 0xFFFL
        require(lo12 <= 0xFFFL) { "add imm12 imposible" }
        val add = u32(0x91000000L or (lo12 shl 10) or (rd.toLong() shl 5) or rd.toLong())
        return adrp to add
    }

    fun decBlTarget(word: Long, va: Long): Long? {
        if ((word and 0xFC000000L) != 0x94000000L) return null
        var imm = word and 0x3FFFFFFL
        if (imm and 0x2000000L != 0L) imm -= 0x4000000L
        return va + imm * 4
    }

    fun decodeBUncond(word: Long, va: Long): Long? {
        if ((word and 0xFC000000L) != 0x14000000L) return null
        var imm = word and 0x3FFFFFFL
        if (imm and 0x2000000L != 0L) imm -= 0x4000000L
        return va + imm * 4
    }

    /** Decode B.cond -> (cond, targetVa). */
    fun decodeBCond(word: Long, va: Long): Pair<Int, Long>? {
        if ((word and 0xFF000000L) != 0x54000000L) return null
        var imm = (word shr 5) and 0x7FFFFL
        if (imm and 0x40000L != 0L) imm -= 0x80000L
        return (word and 0xFL).toInt() to (va + imm * 4)
    }

    fun decodeTbz(word: Long, va: Long): Pair<Int, Long>? {
        if ((word and 0x7F000000L) != 0x36000000L) return null
        var imm = (word shr 5) and 0x3FFFL
        if (imm and 0x2000L != 0L) imm -= 0x4000L
        return ((word shr 19) and 0x1FL).toInt() to (va + imm * 4)
    }

    fun decodeTbnz(word: Long, va: Long): Pair<Int, Long>? {
        if ((word and 0x7F000000L) != 0x37000000L) return null
        var imm = (word shr 5) and 0x3FFFL
        if (imm and 0x2000L != 0L) imm -= 0x4000L
        return ((word shr 19) and 0x1FL).toInt() to (va + imm * 4)
    }

    fun decMovzW(word: Long): Pair<Int, Int>? {
        if ((word and 0xFF800000L) != 0x52800000L) return null
        if ((word shr 21) and 0x3L != 0L) return null
        return (word and 0x1FL).toInt() to ((word shr 5) and 0xFFFFL).toInt()
    }

    fun decodeAarch64BranchTarget(word: Long, source: Long): Long? {
        if ((word and 0xFC000000L) != 0x14000000L) return null
        var imm = word and 0x03FFFFFFL
        if (imm and 0x02000000L != 0L) imm -= 0x04000000L
        return source + imm * 4
    }

    fun decodeAarch64CallTarget(word: Long, source: Long): Long? {
        if ((word and 0xFC000000L) != 0x94000000L) return null
        var imm = word and 0x03FFFFFFL
        if (imm and 0x02000000L != 0L) imm -= 0x04000000L
        return source + imm * 4
    }

    fun decodeAarch64CbzTbzTarget(word: Long, source: Long): Long? {
        if ((word and 0x7E000000L) == 0x34000000L) {
            var imm = (word shr 5) and 0x7FFFFL
            if (imm and 0x40000L != 0L) imm -= 0x80000L
            return source + imm * 4
        }
        if ((word and 0x7E000000L) == 0x36000000L) {
            var imm = (word shr 5) and 0x3FFFL
            if (imm and 0x2000L != 0L) imm -= 0x4000L
            return source + imm * 4
        }
        return null
    }

    fun decodeCondBranchTarget(word: Long, source: Long): Long? {
        if ((word and 0xFF000010L) != 0x54000000L) return null
        var imm = (word shr 5) and 0x7FFFFL
        if (imm and 0x40000L != 0L) imm -= 0x80000L
        return source + imm * 4
    }

    fun decodeMovReg64(raw: ByteArray): Pair<Int, Int>? {
        if (raw.size != 4) return null
        val word = ValBytes.u32At(raw, 0)
        if ((word and 0xFFE0FFE0L) != 0xAA0003E0L) return null
        return (word and 0x1FL).toInt() to ((word shr 16) and 0x1FL).toInt()
    }

    fun branchCond(source: Long, target: Long, cond: Int): ByteArray {
        val delta = target - source
        require(delta % 4 == 0L) { "conditional branch not aligned: 0x${uHex(source)} -> 0x${uHex(target)}" }
        val imm = delta / 4
        require(imm >= -(1L shl 18) && imm < (1L shl 18)) { "conditional branch fuera de rango: 0x${uHex(source)} -> 0x${uHex(target)}" }
        return u32(0x54000000L or ((imm and 0x7FFFFL) shl 5) or (cond and 0xF).toLong())
    }

    fun cbzX(source: Long, target: Long, rt: Int): ByteArray {
        val delta = target - source
        require(delta % 4 == 0L) { "cbz not aligned: 0x${uHex(source)} -> 0x${uHex(target)}" }
        val imm = delta / 4
        require(imm >= -(1L shl 18) && imm < (1L shl 18)) { "cbz fuera de rango: 0x${uHex(source)} -> 0x${uHex(target)}" }
        return u32(0xB4000000L or ((imm and 0x7FFFFL) shl 5) or (rt and 0x1F).toLong())
    }

    fun cbzW(source: Long, target: Long, rt: Int): ByteArray {
        val delta = target - source
        require(delta % 4 == 0L) { "cbz not aligned: 0x${uHex(source)} -> 0x${uHex(target)}" }
        val imm = delta / 4
        require(imm >= -(1L shl 18) && imm < (1L shl 18)) { "cbz fuera de rango: 0x${uHex(source)} -> 0x${uHex(target)}" }
        return u32(0x34000000L or ((imm and 0x7FFFFL) shl 5) or (rt and 0x1F).toLong())
    }

    fun ldrUnsigned(rt: Int, rn: Int, offset: Int, bits: Int): ByteArray {
        val (scale, opcode) = when (bits) {
            64 -> 8 to 0xF9400000L
            32 -> 4 to 0xB9400000L
            else -> throw IllegalArgumentException("bits must be 32 or 64")
        }
        require(offset % scale == 0) { "offset ldr not aligned: $offset" }
        val imm12 = offset / scale
        require(imm12 in 0..0xFFF) { "offset ldr fuera de rango: $offset" }
        return u32(opcode or (imm12.toLong() shl 10) or ((rn and 0x1F).toLong() shl 5) or (rt and 0x1F).toLong())
    }

    fun ldrbUnsigned(rt: Int, rn: Int, offset: Int): ByteArray {
        require(offset in 0..0xFFF) { "offset ldrb fuera de rango: $offset" }
        return u32(0x39400000L or (offset.toLong() shl 10) or ((rn and 0x1F).toLong() shl 5) or (rt and 0x1F).toLong())
    }

    fun ldrbReg(rt: Int, rn: Int, rm: Int): ByteArray =
        u32(0x38606800L or ((rm and 0x1F).toLong() shl 16) or ((rn and 0x1F).toLong() shl 5) or (rt and 0x1F).toLong())

    fun strUnsigned(rt: Int, rn: Int, offset: Int, bits: Int): ByteArray {
        val (scale, opcode) = when (bits) {
            64 -> 8 to 0xF9000000L
            32 -> 4 to 0xB9000000L
            else -> throw IllegalArgumentException("bits must be 32 or 64")
        }
        require(offset % scale == 0) { "offset str not aligned: $offset" }
        val imm12 = offset / scale
        require(imm12 in 0..0xFFF) { "offset str fuera de rango: $offset" }
        return u32(opcode or (imm12.toLong() shl 10) or ((rn and 0x1F).toLong() shl 5) or (rt and 0x1F).toLong())
    }

    fun strbUnsigned(rt: Int, rn: Int, offset: Int): ByteArray {
        require(offset in 0..0xFFF) { "offset strb fuera de rango: $offset" }
        return u32(0x39000000L or (offset.toLong() shl 10) or ((rn and 0x1F).toLong() shl 5) or (rt and 0x1F).toLong())
    }

    fun addImm64(rd: Int, rn: Int, value: Int): ByteArray {
        require(value in 0..0xFFF) { "add immediate fuera de rango: $value" }
        return u32(0x91000000L or (value.toLong() shl 10) or ((rn and 0x1F).toLong() shl 5) or (rd and 0x1F).toLong())
    }

    fun subImm64(rd: Int, rn: Int, value: Int): ByteArray {
        require(value in 0..0xFFF) { "sub immediate fuera de rango: $value" }
        return u32(0xD1000000L or (value.toLong() shl 10) or ((rn and 0x1F).toLong() shl 5) or (rd and 0x1F).toLong())
    }

    fun movReg64(rd: Int, rn: Int): ByteArray =
        u32(0xAA0003E0L or ((rn and 0x1F).toLong() shl 16) or (rd and 0x1F).toLong())

    fun movReg32(rd: Int, rn: Int): ByteArray =
        u32(0x2A0003E0L or ((rn and 0x1F).toLong() shl 16) or (rd and 0x1F).toLong())

    fun movImm64(rd: Int, value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        for (hw in 0..3) {
            val imm16 = ((value shr (hw * 16)) and 0xFFFFL).toInt()
            val opcode = if (hw == 0) 0xD2800000L else 0xF2800000L
            out.addAll(u32(opcode or (hw.toLong() shl 21) or (imm16.toLong() shl 5) or (rd and 0x1F).toLong()).toList())
        }
        return out.toByteArray()
    }

    fun movImm32(rd: Int, value: Long): ByteArray {
        val low = (value and 0xFFFFL).toInt()
        val high = ((value shr 16) and 0xFFFFL).toInt()
        return u32(0x52800000L or (low.toLong() shl 5) or (rd and 0x1F).toLong()) +
            u32(0x72800000L or (1L shl 21) or (high.toLong() shl 5) or (rd and 0x1F).toLong())
    }

    fun cmpReg(rn: Int, rm: Int, bits: Int): ByteArray = when (bits) {
        64 -> u32(0xEB00001FL or ((rm and 0x1F).toLong() shl 16) or ((rn and 0x1F).toLong() shl 5))
        32 -> u32(0x6B00001FL or ((rm and 0x1F).toLong() shl 16) or ((rn and 0x1F).toLong() shl 5))
        else -> throw IllegalArgumentException("bits must be 32 or 64")
    }

    fun cmpImm32(rn: Int, value: Int): ByteArray {
        require(value in 0..0xFFF) { "cmp immediate fuera de rango: $value" }
        return u32(0x7100001FL or (value.toLong() shl 10) or ((rn and 0x1F).toLong() shl 5))
    }

    fun cmnImm32(rn: Int, value: Int): ByteArray {
        require(value in 0..0xFFF) { "cmn immediate fuera de rango: $value" }
        return u32(0x3100001FL or (value.toLong() shl 10) or ((rn and 0x1F).toLong() shl 5))
    }

    fun eorReg32(rd: Int, rn: Int, rm: Int): ByteArray =
        u32(0x4A000000L or ((rm and 0x1F).toLong() shl 16) or ((rn and 0x1F).toLong() shl 5) or (rd and 0x1F).toLong())

    // ---- Thumb (16/32-bit) ----
    fun thumbBW(source: Int, target: Int): ByteArray {
        val delta = target - (source + 4)
        require(delta % 2 == 0) { "thumb branch not aligned: 0x${Integer.toUnsignedString(source, 16)} -> 0x${Integer.toUnsignedString(target, 16)}" }
        val imm = delta / 2
        require(imm >= -(1 shl 24) && imm < (1 shl 24)) { "thumb branch fuera de rango: 0x${Integer.toUnsignedString(source, 16)} -> 0x${Integer.toUnsignedString(target, 16)}" }
        val u = imm and ((1 shl 24) - 1)
        val s = (u shr 23) and 1
        val i1 = (u shr 22) and 1
        val i2 = (u shr 21) and 1
        val imm10 = (u shr 11) and 0x3FF
        val imm11 = u and 0x7FF
        val j1 = (i1 xor s).inv() and 1
        val j2 = (i2 xor s).inv() and 1
        return u16(0xF000 or (s shl 10) or imm10) + u16(0x9000 or (j1 shl 13) or (j2 shl 11) or imm11)
    }

    fun thumbBl(source: Int, target: Int): ByteArray {
        val delta = target - (source + 4)
        require(delta % 2 == 0) { "thumb bl not aligned: 0x${Integer.toUnsignedString(source, 16)} -> 0x${Integer.toUnsignedString(target, 16)}" }
        val imm = delta / 2
        require(imm >= -(1 shl 23) && imm < (1 shl 23)) { "thumb bl fuera de rango: 0x${Integer.toUnsignedString(source, 16)} -> 0x${Integer.toUnsignedString(target, 16)}" }
        val u = imm and ((1 shl 24) - 1)
        val s = (u shr 23) and 1
        val i1 = (u shr 22) and 1
        val i2 = (u shr 21) and 1
        val imm10 = (u shr 11) and 0x3FF
        val imm11 = u and 0x7FF
        val j1 = (i1 xor s).inv() and 1
        val j2 = (i2 xor s).inv() and 1
        return u16(0xF000 or (s shl 10) or imm10) + u16(0xD000 or (j1 shl 13) or (1 shl 12) or (j2 shl 11) or imm11)
    }

    fun thumbBCond16(source: Int, target: Int, cond: Int): ByteArray {
        val delta = target - (source + 4)
        require(delta % 2 == 0) { "thumb conditional branch not aligned: 0x${Integer.toUnsignedString(source, 16)} -> 0x${Integer.toUnsignedString(target, 16)}" }
        val imm = delta / 2
        require(imm in -128..127) { "thumb conditional branch fuera de rango: 0x${Integer.toUnsignedString(source, 16)} -> 0x${Integer.toUnsignedString(target, 16)}" }
        return u16(0xD000 or ((cond and 0xF) shl 8) or (imm and 0xFF))
    }

    fun thumbCmpImm(reg: Int, value: Int): ByteArray {
        require(reg in 0..7 && value in 0..0xFF) { "thumb cmp immediate fuera de rango" }
        return u16(0x2800 or ((reg and 7) shl 8) or value)
    }

    fun thumbMovsImm(reg: Int, value: Int): ByteArray {
        require(reg in 0..7 && value in 0..0xFF) { "thumb movs immediate fuera de rango" }
        return u16(0x2000 or ((reg and 7) shl 8) or value)
    }

    fun thumbAddImm3(rd: Int, rn: Int, value: Int): ByteArray {
        require(rd in 0..7 && rn in 0..7 && value in 0..7) { "thumb add immediate fuera de rango" }
        return u16(0x1C00 or ((value and 7) shl 6) or ((rn and 7) shl 3) or (rd and 7))
    }

    fun thumbLdrImm(rt: Int, rn: Int, offset: Int): ByteArray {
        require(rt in 0..7 && rn in 0..7 && offset % 4 == 0 && offset in 0..124) { "thumb ldr immediate fuera de rango" }
        return u16(0x6800 or ((offset / 4) shl 6) or ((rn and 7) shl 3) or (rt and 7))
    }

    fun thumbLdrbImm(rt: Int, rn: Int, offset: Int): ByteArray {
        require(rt in 0..7 && rn in 0..7 && offset in 0..31) { "thumb ldrb immediate fuera de rango" }
        return u16(0x7800 or ((offset and 0x1F) shl 6) or ((rn and 7) shl 3) or (rt and 7))
    }

    fun thumbStrbImm(rt: Int, rn: Int, offset: Int): ByteArray {
        require(rt in 0..7 && rn in 0..7 && offset in 0..31) { "thumb strb immediate fuera de rango" }
        return u16(0x7000 or ((offset and 0x1F) shl 6) or ((rn and 7) shl 3) or (rt and 7))
    }

    fun thumbEors(rd: Int, rm: Int): ByteArray {
        require(rd in 0..7 && rm in 0..7) { "thumb eors fuera de rango" }
        return u16(0x4040 or ((rm and 7) shl 3) or (rd and 7))
    }

    fun thumbPush(regs: List<Int>): ByteArray {
        var low = 0
        var lr = false
        for (reg in regs) {
            when {
                reg in 0..7 -> low = low or (1 shl reg)
                reg == 14 -> lr = true
                else -> throw IllegalArgumentException("thumb push only supports r0-r7 and lr")
            }
        }
        return u16(0xB400 or (if (lr) 0x100 else 0) or low)
    }

    fun thumbPop(regs: List<Int>): ByteArray {
        var low = 0
        var pcBit = false
        for (reg in regs) {
            when {
                reg in 0..7 -> low = low or (1 shl reg)
                reg == 15 -> pcBit = true
                else -> throw IllegalArgumentException("thumb pop only supports r0-r7 and pc")
            }
        }
        return u16(0xBC00 or (if (pcBit) 0x100 else 0) or low)
    }

    fun thumbSubSp(value: Int): ByteArray {
        require(value in 0..0x1FC && value % 4 == 0) { "thumb sub sp immediate fuera de rango" }
        return u16(0xB080 or (value / 4))
    }

    fun thumbAddSp(value: Int): ByteArray {
        require(value in 0..0x1FC && value % 4 == 0) { "thumb add sp immediate fuera de rango" }
        return u16(0xB000 or (value / 4))
    }

    fun thumbAddSpToReg(rd: Int, value: Int): ByteArray {
        require(rd in 0..7 && value in 0..0x3FC && value % 4 == 0) { "thumb add rd,sp,#imm fuera de rango" }
        return u16(0xA800 or ((rd and 7) shl 8) or (value / 4))
    }

    fun thumbMovReg(rd: Int, rm: Int): ByteArray {
        require(rd in 0..15 && rm in 0..15) { "thumb mov reg fuera de rango" }
        return u16(0x4600 or ((rm and 0xF) shl 3) or (rd and 0x7) or ((rd and 0x8) shl 4))
    }

    fun thumbStrImm(rt: Int, rn: Int, offset: Int): ByteArray {
        require(rt in 0..7 && rn in 0..7 && offset % 4 == 0 && offset in 0..124) { "thumb str immediate fuera de rango" }
        return u16(0x6000 or ((offset / 4) shl 6) or ((rn and 7) shl 3) or (rt and 7))
    }

    fun thumbStrSpImm(rt: Int, offset: Int): ByteArray {
        require(rt in 0..7 && offset % 4 == 0 && offset in 0..1020) { "thumb str sp immediate fuera de rango" }
        return u16(0x9000 or ((rt and 7) shl 8) or (offset / 4))
    }

    fun thumbCmpReg(rn: Int, rm: Int): ByteArray {
        require(rn in 0..7 && rm in 0..7) { "thumb cmp reg fuera de rango" }
        return u16(0x4280 or ((rm and 7) shl 3) or (rn and 7))
    }

    fun thumbBxLr(): ByteArray = u16(0x4770)

    fun isThumbBl(data: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + 4 > data.size) return false
        val first = ValBytes.u16At(data, offset)
        val second = ValBytes.u16At(data, offset + 2)
        return (first and 0xF800) == 0xF000 && (second and 0xD000) == 0xD000
    }

    fun isThumbCbnzR0(data: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + 2 > data.size) return false
        val word = ValBytes.u16At(data, offset)
        return (word and 0xF507) == 0xB100 && (word and 0x0800) != 0
    }
}
