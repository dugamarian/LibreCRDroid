package re.abbot.librecr.protocol.crypto

/**
 * Clean-room port of Abbott's first-pair Phase 5 source builder (Swift
 * `FirstPairSourceSlice`). This is the large, layered white-box construction
 * that turns the sensor's ephemeral+static P-256 points + bundled entry source +
 * locally-generated entropy into the 66-byte Phase 5 source. Ported bottom-up,
 * one verified layer per increment.
 *
 * Layers ported so far:
 *   - VM core (step / vm67cc18 / vm67cecc / vm67d524 / vm67076c) + table loader
 *   - DF80 transform (workspace, expanded schedule, compress state)
 *
 * All VM math uses Int with explicit `and 0xff` masking; "magic" constants are
 * Long, decoded exactly as in the Swift original.
 */
internal object FirstPairSourceSlice {

    // ---- DF80 ----

    fun df80Transform(state: ByteArray, blocks: ByteArray): ByteArray {
        val workspace = df80InitialWorkspace(blocks)
        val schedule = df80ExpandedSchedule(workspace)
        return df80CompressState(state, schedule)
    }

    fun df80InitialWorkspace(blocks: ByteArray): ByteArray {
        if (blocks.size != DF80_INPUT_BLOCK_COUNT * BLOCK66_SIZE) throw FirstPairSliceException("df80 block length ${blocks.size}")
        val t = Tables.shared
        val workspace = ByteArray(DF80_INITIAL_WORKSPACE_SIZE)
        for (index in 0 until DF80_INPUT_BLOCK_COUNT) {
            val start = index * BLOCK66_SIZE
            val dst = index * DF80_INITIAL_WORKSPACE_STRIDE
            val src = blocks.copyOfRange(start, start + BLOCK66_SIZE)
            val sideA = vm67cc18(0x22000002444L, src, src, t)
            val sideB = vm67cecc(0x22008004942L, src, src, t)
            vm67cc18(0x12000000deaL, sideA, sideA, t).copyInto(workspace, dst + 0x36)
            vm67cecc(0x12004003dcdL, sideA, sideA, t).copyInto(workspace, dst + 0x24)
            vm67cc18(0x120000052bbL, sideB, sideB, t).copyInto(workspace, dst + 0x12)
            vm67cecc(0x12004003d69L, sideB, sideB, t).copyInto(workspace, dst)
        }
        return workspace
    }

    fun df80ExpandedSchedule(initialWorkspace: ByteArray): ByteArray {
        if (initialWorkspace.size != DF80_INITIAL_WORKSPACE_SIZE) throw FirstPairSliceException("df80 workspace ${initialWorkspace.size}")
        val t = Tables.shared
        val schedule = initialWorkspace + ByteArray(DF80_DERIVED_SCHEDULE_SIZE)
        var offset = 0
        while (offset < DF80_DERIVED_SCHEDULE_SIZE) {
            val w0 = schedule.copyOfRange(offset, offset + DF80_WORD_SIZE)
            val w1 = schedule.copyOfRange(offset + 0x12, offset + 0x24)
            val w9 = schedule.copyOfRange(offset + 0xa2, offset + 0xb4)
            val w14 = schedule.copyOfRange(offset + 0xfc, offset + 0x10e)

            var tmp22 = vm67cecc(0x2400900240463cL, w14, w14, t)
            var tmp80 = vm67cc18(0x12000005af5L, w14, w14, t)
            var tmp58 = packDF80Zeros6Marker(0x06, tmp80)
            var tmp34 = vm67cc18(0x12000005ddfL, tmp58, tmp58, t)
            var tmpa4 = vm67cc18(0x12000000523L, tmp34, tmp22, t)

            tmp22 = vm67cecc(0x2800800280004aL, w14, w14, t)
            tmp80 = vm67cc18(0x12000002717L, w14, w14, t)
            tmp58 = packDF80Zeros5Marker6(tmp80)
            tmp34 = vm67cc18(0x1200000304eL, tmp58, tmp58, t)
            tmp58 = vm67cc18(0x120000016d7L, tmp34, tmp22, t)

            tmp80 = vm67cecc(0x1400d001403ea3L, w14, w14, t)
            tmp22 = vm67cc18(0x120000037a4L, tmpa4, tmp58, t)
            val tmp92 = vm67cc18(0x120000034f0L, tmp80, tmp22, t)
            tmpa4 = vm67cc18(0x1200000050bL, tmp92, w9, t)

            tmp22 = vm67cecc(0x1000e0010042f8L, w1, w1, t)
            tmp80 = vm67cc18(0x12000000251L, w1, w1, t)
            tmp58 = packDF80Zeros11Marker5(tmp80)
            tmp34 = vm67cc18(0x12000005e68L, tmp58, tmp58, t)
            val tmp118 = vm67cc18(0x12000004120L, tmp34, tmp22, t)

            tmp80 = vm67cecc(0x24009002402391L, w1, w1, t)
            tmp58 = packDF80Zeros6Marker(0x07, w1)
            tmp22 = vm67cc18(0x12000000da4L, tmp58, tmp58, t)
            tmp34 = vm67cc18(0x12000003e91L, tmp22, tmp80, t)

            tmp80 = vm67cecc(0x08010000802f7eL, w1, w1, t)
            tmp22 = vm67cc18(0x120000041eaL, tmp118, tmp34, t)
            tmp58 = vm67cc18(0x12000000846L, tmp80, tmp22, t)
            tmp80 = vm67cc18(0x120000019eaL, tmp58, w0, t)
            val derived = vm67cc18(0x12000003ac8L, tmpa4, tmp80, t)
            derived.copyInto(schedule, offset + DF80_INITIAL_WORKSPACE_SIZE)
            offset += DF80_WORD_SIZE
        }
        return schedule
    }

    fun df80CompressState(state: ByteArray, schedule: ByteArray): ByteArray {
        if (state.size != DF80_STATE_SIZE) throw FirstPairSliceException("df80 state ${state.size}")
        if (schedule.size != DF80_SCHEDULE_SIZE) throw FirstPairSliceException("df80 schedule ${schedule.size}")
        val t = Tables.shared
        val original = Array(8) { state.copyOfRange(it * DF80_WORD_SIZE, (it + 1) * DF80_WORD_SIZE) }

        var s0 = vm67cc18(0x1200000189dL, original[0], original[0], t)
        var s1 = vm67cc18(0x12000001e60L, original[1], original[1], t)
        var s2 = vm67cc18(0x1200000152dL, original[2], original[2], t)
        var s3 = vm67cc18(0x120000029edL, original[3], original[3], t)
        var s4 = vm67cc18(0x120000040ecL, original[4], original[4], t)
        var s5 = vm67cc18(0x120000036edL, original[5], original[5], t)
        var s6 = vm67cc18(0x12000003423L, original[6], original[6], t)
        var s7 = vm67cc18(0x12000004056L, original[7], original[7], t)

        val staticB = checkedSlice(t.df80RoundTables, DF80_SCHEDULE_SIZE, DF80_WORD_SIZE)

        var offset = 0
        while (offset < DF80_SCHEDULE_SIZE) {
            val word = schedule.copyOfRange(offset, offset + DF80_WORD_SIZE)
            val roundA = checkedSlice(t.df80RoundTables, offset, DF80_WORD_SIZE)

            var tmp80 = vm67cecc(0x0c00f000c01a40L, s4, s4, t)
            var tmp58 = packDF80Zeros12Marker3(s4)
            var tmp22 = vm67cc18(0x120000032dcL, tmp58, tmp58, t)
            val mix10 = vm67cc18(0x12000001105L, tmp22, tmp80, t)

            tmp22 = vm67cecc(0x1800c0018050f7L, s4, s4, t)
            tmp80 = vm67cc18(0x12000002cc3L, s4, s4, t)
            tmp58 = packDF80Zeros8Zero6(tmp80)
            var t64 = vm67cc18(0x120000030c4L, tmp58, tmp58, t)
            val mix14 = vm67cc18(0x12000001251L, t64, tmp22, t)

            val mix15 = vm67cecc(0x34005003403785L, s4, s4, t)
            tmp80 = vm67cc18(0x120000025a0L, s4, s4, t)
            tmp58 = packDF80Zeros2Marker6(tmp80)
            val mix17 = vm67cc18(0x12000000d92L, tmp58, tmp58, t)
            val mix18 = vm67cc18(0x120000026f3L, mix17, mix15, t)
            val mix19 = vm67cc18(0x12000000e82L, mix10, mix14, t)
            var t76 = vm67cc18(0x120000023eeL, mix18, mix19, t)
            t76 = vm67cc18(0x120000043a5L, s7, t76, t)

            t64 = vm67cc18(0x12000005386L, roundA, word, t)
            val t52 = vm67cc18(0x12000004501L, t76, t64, t)

            tmp58 = vm67cc18(0x12000000ce4L, s4, s5, t)
            tmp80 = vm67cc18(0x12000003aa4L, staticB, s4, t)
            tmp22 = vm67cc18(0x12000000f71L, tmp80, s6, t)
            val t40 = vm67cc18(0x120000020bcL, tmp58, tmp22, t)
            val tmp92 = vm67cc18(0x12000000aa6L, t52, t40, t)

            tmp80 = vm67cecc(0x04011000404984L, s0, s0, t)
            tmp58 = packDF80Zeros14Marker1(s0)
            tmp22 = vm67cc18(0x1200000190bL, tmp58, tmp58, t)
            val t2e = vm67cc18(0x12000003cd1L, tmp22, tmp80, t)

            tmp22 = vm67cecc(0x1c00b001c048a7L, s0, s0, t)
            tmp80 = vm67cc18(0x12000003683L, s0, s0, t)
            tmp58 = packDF80Zeros9(tmp80)
            tmp58 = vm67cc18(0x120000017b1L, tmp58, tmp58, t)
            val t1c = vm67cc18(0x12000000d5eL, tmp58, tmp22, t)

            tmp80 = vm67cecc(0x2c007002c000baL, s0, s0, t)
            tmp58 = packDF80Zeros4Marker3(s0)
            tmp22 = vm67cc18(0x120000001fdL, tmp58, tmp58, t)
            val tmp34First = vm67cc18(0x12000006062L, tmp22, tmp80, t)

            tmp80 = vm67cc18(0x12000004ffbL, t2e, t1c, t)
            tmp58 = vm67cc18(0x120000032caL, tmp34First, tmp80, t)

            tmp22 = vm67cc18(0x12000000d08L, s0, s1, t)
            val tmp34 = vm67cc18(0x120000019b6L, s0, s2, t)
            val t2eSecond = vm67cc18(0x12000004352L, s1, s2, t)
            val t1cSecond = vm67cc18(0x12000000f5fL, tmp22, tmp34, t)
            tmp80 = vm67cc18(0x12000004010L, t2eSecond, t1cSecond, t)
            val tmpa4 = vm67cc18(0x120000035d7L, tmp58, tmp80, t)

            val newS7 = vm67cc18(0x12000003bc0L, s6, s6, t)
            val newS6 = vm67cc18(0x120000042aaL, s5, s5, t)
            val newS5 = vm67cc18(0x120000042bcL, s4, s4, t)
            val newS4 = vm67cc18(0x12000006050L, s3, tmp92, t)
            val newS3 = vm67cc18(0x120000047c5L, s2, s2, t)
            val newS2 = vm67cc18(0x12000004e83L, s1, s1, t)
            val newS1 = vm67cc18(0x120000055c9L, s0, s0, t)
            val newS0 = vm67cc18(0x12000002088L, tmp92, tmpa4, t)

            s0 = newS0; s1 = newS1; s2 = newS2; s3 = newS3
            s4 = newS4; s5 = newS5; s6 = newS6; s7 = newS7
            offset += DF80_WORD_SIZE
        }

        val out = ByteArray(DF80_STATE_SIZE)
        vm67cc18(0x12000001eb4L, original[0], s0, t).copyInto(out, 0 * DF80_WORD_SIZE)
        vm67cc18(0x12000005b5bL, original[1], s1, t).copyInto(out, 1 * DF80_WORD_SIZE)
        vm67cc18(0x120000042e6L, original[2], s2, t).copyInto(out, 2 * DF80_WORD_SIZE)
        vm67cc18(0x12000000b94L, original[3], s3, t).copyInto(out, 3 * DF80_WORD_SIZE)
        vm67cc18(0x1200000383aL, original[4], s4, t).copyInto(out, 4 * DF80_WORD_SIZE)
        vm67cc18(0x12000003581L, original[5], s5, t).copyInto(out, 5 * DF80_WORD_SIZE)
        vm67cc18(0x12000004deaL, original[6], s6, t).copyInto(out, 6 * DF80_WORD_SIZE)
        vm67cc18(0x12000000dd8L, original[7], s7, t).copyInto(out, 7 * DF80_WORD_SIZE)
        return out
    }

    // ---- 679f48 descriptor inputs (vm-only sub-layers) ----

    /** Convert previous descriptor blocks into 67dd7c update blocks. */
    fun previousDescriptorBlocksToDD7CInputs(previousBlocks: ByteArray): ByteArray {
        if (previousBlocks.size % BLOCK66_SIZE != 0) throw FirstPairSliceException("encoded block length ${previousBlocks.size}")
        val t = Tables.shared
        val out = ByteArray(previousBlocks.size)
        var start = 0
        while (start < previousBlocks.size) {
            val block = previousBlocks.copyOfRange(start, start + BLOCK66_SIZE)
            val encoded = vm67cc18(0x42000001341L, block, block, t)
            val staged = vm67cc18(0x420000053baL, encoded, encoded, t)
            vm67cc18(0x42000000c2cL, staged, staged, t).copyInto(out, start)
            start += BLOCK66_SIZE
        }
        return out
    }

    fun constructor670978Ptr28Blocks(rawDescriptorBlocks: ByteArray): ByteArray =
        constructor67076cBlocks(rawDescriptorBlocks, 0x42000000000L)

    fun constructor670a54Ptr10Blocks(rawDescriptorBlocks: ByteArray): ByteArray =
        constructor67076cBlocks(rawDescriptorBlocks, 0x42000000042L)

    private fun constructor67076cBlocks(rawDescriptorBlocks: ByteArray, magic: Long): ByteArray {
        if (rawDescriptorBlocks.size % BLOCK66_SIZE != 0) throw FirstPairSliceException("encoded block length ${rawDescriptorBlocks.size}")
        val t = Tables.shared
        val out = ByteArray(rawDescriptorBlocks.size)
        var start = 0
        while (start < rawDescriptorBlocks.size) {
            val block = rawDescriptorBlocks.copyOfRange(start, start + BLOCK66_SIZE)
            vm67076c(magic, block, block, t).copyInto(out, start)
            start += BLOCK66_SIZE
        }
        return out
    }

    // ---- 679f48 context state machine + derive chain ----

    fun init679f48Context(): ByteArray {
        val t = Tables.shared
        val context = ByteArray(CONTEXT_679F48_SIZE)
        for (spec in INIT_679F48_BLOCK18_SPECS) {
            val src = checkedSlice(t.seedTables679f48, spec[1].toInt(), DF80_WORD_SIZE)
            vm67cc18(spec[0], src, src, t).copyInto(context, spec[2].toInt())
        }
        val src66 = checkedSlice(t.seedTables679f48, 0, BLOCK66_SIZE)
        val block66 = vm67cc18(0x42000001e72L, src66, src66, t)
        for (dst in intArrayOf(0x08, 0x4a, 0x8c, 0xce)) block66.copyInto(context, dst)
        return context
    }

    fun update67aa8cLen4Initial(context: ByteArray, src4: ByteArray): ByteArray {
        require(src4.size == 4)
        val t = Tables.shared
        val ctx = context.copyOf()
        require(ctx[0x1a4].toInt() == 0)
        ctx[0x1a4] = 1
        seed67aa8cInitialWords(ctx, t)
        src4.copyInto(ctx, 0x1a5)
        writeU32LE(ctx, 0x1e8, 4)
        return ctx
    }

    private fun seed67aa8cInitialWords(ctx: ByteArray, t: Tables) {
        for (spec in AA8C_INITIAL_REDUCER_SPECS) {
            val window = ctx.copyOfRange(spec[1].toInt(), spec[1].toInt() + DF80_WORD_SIZE)
            val reduced = reducer67ea28Word(vm67cecc(spec[0], window, window, t), t)
            reduced.copyInto(ctx, spec[2].toInt())
        }
    }

    private fun reducer67ea28Word(src: ByteArray, t: Tables): ByteArray {
        val tmp18 = vm67cc18(0x120000048e2L, src, src, t)
        var state18 = vm67d524(0xc00f000c0578eL, tmp18, t)
        var packed = 0
        var outShift = 0
        var bitBudget = 0x20
        for (roundIndex in 0 until 8) {
            val scratch4 = vm67cc18(0x40000033d7L, state18, state18, t)
            if (bitBudget >= 5) state18 = vm67cecc(0x8010000805f94L, state18, state18, t)
            val tmp4 = vm67cc18(0x4000004513L, scratch4, scratch4, t)
            val tableIndex = (tmp4[2].toInt() and 0xff) xor ((tmp4[3].toInt() and 0xff) shl 3)
            val tableByte = t.reducer67ea28Nibble[tableIndex].toInt() and 0xff
            val nibble = if (roundIndex and 1 == 0) tableByte and 0x0f else tableByte shr 4
            val mask: Int = when {
                bitBudget >= 4 -> -1.also { bitBudget -= 4 }
                bitBudget == 0 -> 0
                else -> ((1 shl bitBudget) - 1).also { bitBudget = 0 }
            }
            packed = packed or ((nibble and mask) shl outShift)
            outShift += 4
        }
        return byteArrayOf(
            (packed and 0xff).toByte(), ((packed ushr 8) and 0xff).toByte(),
            ((packed ushr 16) and 0xff).toByte(), ((packed ushr 24) and 0xff).toByte(),
        )
    }

    fun apply67eb94PendingBlocks(context: ByteArray): ByteArray {
        val t = Tables.shared
        val ctx = context.copyOf()
        if (ctx[0x1a4].toInt() == 0) return ctx
        ctx[0x1a4] = 0
        val words = Array(8) { ctx.copyOfRange(0x1ec + it * 4, 0x1ec + it * 4 + 4) }
        update67eb94Blocks(words, t).copyInto(ctx, 0x114)
        return ctx
    }

    private fun update67eb94Blocks(words: Array<ByteArray>, t: Tables): ByteArray {
        val out = ByteArray(DF80_STATE_SIZE)
        var pos = 0
        for (i in 0 until 8) {
            val expanded = expand67ed24(words[i], t)
            vm67cc18(EB94_UPDATE_MAGICS[i], expanded, expanded, t).copyInto(out, pos)
            pos += DF80_WORD_SIZE
        }
        return out
    }

    private fun expand67ed24(wordLE: ByteArray, t: Tables): ByteArray {
        val sideA = expandWordTrits(wordLE, 0x0870, t)
        val sideB = expandWordTrits(wordLE, 0x0b70, t)
        val foldedA = fold24To18(0x600000133bL, 0x6000003479L, sideA, t)
        val wideA = vm67cecc(0x40012000000028L, foldedA, foldedA, t)
        val foldedB = fold24To18(0x6000004936L, 0x6000000000L, sideB, t)
        val wideB = vm67cecc(0x40012000004683L, foldedB, foldedB, t)
        val mixed = vm67cc18(0x22000004d74L, wideA, wideB, t)
        return vm67d524(0xc01f000c05d34L, mixed, t)
    }

    private fun expandWordTrits(wordLE: ByteArray, tableOffset: Int, t: Tables): ByteArray {
        val out = ByteArray(24)
        var o = 0
        for (i in 0 until 4) {
            val index = tableOffset + (wordLE[i].toInt() and 0xff) * 3
            for (k in 0 until 3) {
                val v = t.finalizerTables[index + k].toInt() and 0xff
                out[o++] = (v and 7).toByte(); out[o++] = (v shr 3).toByte()
            }
        }
        return out
    }

    private fun fold24To18(firstMagic: Long, tailMagic: Long, src24: ByteArray, t: Tables): ByteArray {
        val first = vm67cc18(firstMagic, src24, src24, t)
        val tail = ArrayList<Byte>(18)
        var offset = 6
        while (offset <= 18) {
            val src = src24.copyOfRange(offset, src24.size)
            for (b in vm67cc18(tailMagic, src, src, t)) tail.add(b)
            offset += 6
        }
        val out = ArrayList<Byte>()
        for (b in first) out.add(b)
        for (i in 2 until 6) out.add(tail[i])
        for (i in 8 until 12) out.add(tail[i])
        for (i in 14 until 18) out.add(tail[i])
        return out.toByteArray()
    }

    fun encode67d630Block(src: ByteArray): ByteArray {
        require(src.isNotEmpty() && src.size <= 0x10)
        val t = Tables.shared
        val scratch16 = ByteArray(16)
        for (i in src.indices) scratch16[16 - src.size + i] = src[src.size - 1 - i]
        val sideA = ArrayList<Byte>(96)
        val sideB = ArrayList<Byte>(96)
        for (byte in scratch16) {
            for (b in expandRawByte67d630(byte, 0x0d2, t)) sideA.add(b)
            for (b in expandRawByte67d630(byte, 0x3d2, t)) sideB.add(b)
        }
        val foldedA = fold96To66(0x600000032b2L, 0x60000005e9cL, sideA.toByteArray(), t)
        val mixedA = vm67cc18(0x42000004b29L, foldedA, foldedA, t)
        val foldedB = fold96To66(0x60000000133L, 0x600000033dbL, sideB.toByteArray(), t)
        val mixedB = vm67cc18(0x42000000263L, foldedB, foldedB, t)
        val mixed = vm67cc18(0x42000000b0eL, mixedA, mixedB, t)
        return vm67d524(0xc03f000c0112fL, mixed, t)
    }

    private fun expandRawByte67d630(byte: Byte, tableOffset: Int, t: Tables): ByteArray {
        val index = tableOffset + (byte.toInt() and 0xff) * 3
        val out = ByteArray(6)
        var o = 0
        for (k in 0 until 3) {
            val v = t.seedTables679f48[index + k].toInt() and 0xff
            out[o++] = (v and 7).toByte(); out[o++] = (v shr 3).toByte()
        }
        return out
    }

    private fun fold96To66(firstMagic: Long, tailMagic: Long, src96: ByteArray, t: Tables): ByteArray {
        val padded = src96 + ByteArray(0x60)
        val first = vm67cc18(firstMagic, padded.copyOfRange(0, 0x60), padded.copyOfRange(0, 0x60), t)
        val out = ArrayList<Byte>(66)
        for (i in 0 until 6) out.add(first[i])
        var offset = 6
        while (offset < 0x60) {
            val src = padded.copyOfRange(offset, offset + 0x60)
            val chunk = vm67cc18(tailMagic, src, src, t)
            for (i in 2 until 6) out.add(chunk[i])
            offset += 6
        }
        return out.toByteArray()
    }

    fun apply67eb94WithPendingRawAdapter(context: ByteArray): ByteArray {
        var ctx = context.copyOf()
        if (ctx[0x1a4].toInt() == 0) return ctx
        val pendingLength = readU32LE(ctx, 0x1e8)
        require(pendingLength <= 0x40)
        ctx = apply67eb94PendingBlocks(ctx)
        val pending = ctx.copyOfRange(0x1a5, 0x1a5 + pendingLength)
        var offset = 0
        while (offset < pending.size) {
            val end = minOf(offset + 0x10, pending.size)
            val chunk = pending.copyOfRange(offset, end)
            val encoded = encode67d630Block(chunk)
            ctx = apply67dd7cUpdateUntilDF80(ctx, encoded, chunk.size)
            offset = end
        }
        writeU32LE(ctx, 0x1e8, 0)
        return ctx
    }

    fun apply67dd7cUpdateUntilDF80(context: ByteArray, encoded66: ByteArray, rawLength: Int): ByteArray {
        require(encoded66.size == BLOCK66_SIZE)
        require(rawLength in 1..0x10)
        val t = Tables.shared
        val ctx = context.copyOf()
        val contextLength = readU64LE(ctx, 0)
        val low = (contextLength and 0x0f).toInt()
        val room = 0x10 - low
        var blockIndex = readU32LE(ctx, 0x110)
        val slot = 0x08 + blockIndex * BLOCK66_SIZE

        if (low != 0) {
            var staged = vm67cc18(0x42000005c05L, encoded66, encoded66, t)
            repeat(low) { staged = vm67cecc(0x1003e001002eafL, staged, staged, t) }
            val pad = checkedSlice(t.finalizerTables, (low xor 0x0f) * BLOCK66_SIZE, BLOCK66_SIZE)
            val current = checkedSlice(ctx, slot, BLOCK66_SIZE)
            val prefix = vm67cc18(0x42000002fd4L, current, pad, t)
            vm67cc18(0x42000003060L, prefix, staged, t).copyInto(ctx, slot)
        } else {
            vm67cc18(0x42000001c66L, encoded66, encoded66, t).copyInto(ctx, slot)
        }

        if (room <= rawLength) {
            blockIndex += 1
            writeU32LE(ctx, 0x110, blockIndex)
            if (blockIndex == 4) {
                val transformed = df80Transform(ctx.copyOfRange(0x114, 0x1a4), ctx.copyOfRange(0x08, 0x110))
                transformed.copyInto(ctx, 0x114)
                blockIndex = 0
                writeU32LE(ctx, 0x110, 0)
            }
            if (room < rawLength) {
                var remainder = vm67cc18(0x42000003d8bL, encoded66, encoded66, t)
                repeat(room) { remainder = shift67dd7cRemainder(remainder, t) }
                val nextSlot = 0x08 + blockIndex * BLOCK66_SIZE
                vm67cc18(0x420000008deL, remainder, remainder, t).copyInto(ctx, nextSlot)
            }
        }
        writeU64LE(ctx, 0, contextLength + rawLength.toLong())
        return ctx
    }

    private fun shift67dd7cRemainder(block66: ByteArray, t: Tables): ByteArray {
        val shifted = byteArrayOf(0, 0, 0, 3) + block66.copyOfRange(0, 0x3e)
        return vm67cc18(0x42000001974L, shifted, shifted, t)
    }

    fun finalize679f48ToSecondDF80(context: ByteArray): ByteArray {
        val t = Tables.shared
        val ctx = context.copyOf()
        val contextLength = readU64LE(ctx, 0)
        val low = (contextLength and 0x0f).toInt()
        var blockIndex = readU32LE(ctx, 0x110)
        val slot = 0x08 + blockIndex * BLOCK66_SIZE

        if (low != 0) {
            val padIndex = low xor 0x0f
            val pad1 = checkedSlice(t.finalizerTables, padIndex * BLOCK66_SIZE, BLOCK66_SIZE)
            val pad2 = checkedSlice(t.finalizerTables, 0x0eb2 + padIndex * BLOCK66_SIZE, BLOCK66_SIZE)
            val current = checkedSlice(ctx, slot, BLOCK66_SIZE)
            val mixed = vm67cc18(0x42000005702L, current, pad1, t)
            vm67cc18(0x42000005c47L, mixed, pad2, t).copyInto(ctx, slot)
        } else {
            val staticBlock = checkedSlice(t.finalizerTables, 0x0e70, BLOCK66_SIZE)
            vm67cc18(0x42000000ffbL, staticBlock, staticBlock, t).copyInto(ctx, slot)
        }

        if (low > 7 || blockIndex <= 2) {
            blockIndex += 1
            writeU32LE(ctx, 0x110, blockIndex)
            if (blockIndex == 4) {
                val transformed = df80Transform(ctx.copyOfRange(0x114, 0x1a4), ctx.copyOfRange(0x08, 0x110))
                transformed.copyInto(ctx, 0x114)
                blockIndex = 0
                writeU32LE(ctx, 0x110, 0)
            }
            if (blockIndex <= 3) {
                val staticBlock = checkedSlice(t.finalizerTables, 0x1290, BLOCK66_SIZE)
                while (blockIndex < 4) {
                    val fillSlot = 0x08 + blockIndex * BLOCK66_SIZE
                    vm67cc18(0x42000005d9dL, staticBlock, staticBlock, t).copyInto(ctx, fillSlot)
                    blockIndex += 1
                    writeU32LE(ctx, 0x110, blockIndex)
                }
            }
        }

        writeU64LE(ctx, 0, contextLength shl 3)
        val finalLength = final679f48LengthBlock(contextLength)
        val finalMixed = vm67cc18(0x420000040aaL, finalLength, ctx.copyOfRange(0xce, 0x110), t)
        finalMixed.copyInto(ctx, 0xce)
        val transformed = df80Transform(ctx.copyOfRange(0x114, 0x1a4), ctx.copyOfRange(0x08, 0x110))
        transformed.copyInto(ctx, 0x114)
        return ctx
    }

    fun final679f48LengthBlock(contextLength: Long): ByteArray {
        val t = Tables.shared
        val bitLength = contextLength shl 3
        val sideA = expandU64Trits(bitLength, 0, t)
        val sideB = expandU64Trits(bitLength, 0x300, t)
        val foldedA = fold48To34(0x600000051dL, 0x6000002556L, sideA, t)
        val laneA = vm67cecc(0x800220000010a1L, foldedA, foldedA, t)
        val foldedB = fold48To34(0x60000018afL, 0x6000005ee6L, sideB, t)
        val laneB = vm67cecc(0x80022000004224L, foldedB, foldedB, t)
        val mixed = vm67cc18(0x420000007c0L, laneA, laneB, t)
        return vm67d524(0xc03f000c0192fL, mixed, t)
    }

    private fun expandU64Trits(value: Long, tableOffset: Int, t: Tables): ByteArray {
        val out = ArrayList<Byte>(48)
        var shift = 0
        while (shift < 64) {
            val index = tableOffset + (((value ushr shift) and 0xff).toInt()) * 3
            for (k in 0 until 3) {
                val v = t.finalLenTables[index + k].toInt() and 0xff
                out.add((v and 7).toByte()); out.add((v shr 3).toByte())
            }
            shift += 8
        }
        return out.toByteArray()
    }

    private fun fold48To34(firstMagic: Long, tailMagic: Long, src48: ByteArray, t: Tables): ByteArray {
        val out = ArrayList<Byte>(34)
        for (b in vm67cc18(firstMagic, src48, src48, t)) out.add(b)
        var offset = 6
        while (offset < 0x30) {
            val src = src48.copyOfRange(offset, src48.size)
            val chunk = vm67cc18(tailMagic, src, src, t)
            for (i in 2 until 6) out.add(chunk[i])
            offset += 6
        }
        return out.toByteArray()
    }

    fun finalized679f48ContextFromInputs(previousBlocks: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1)): ByteArray {
        var ctx = init679f48Context()
        ctx = update67aa8cLen4Initial(ctx, src4)
        ctx = apply67eb94WithPendingRawAdapter(ctx)
        val fullUpdates = previousDescriptorBlocksToDD7CInputs(previousBlocks)
        var start = 0
        while (start < fullUpdates.size) {
            ctx = apply67dd7cUpdateUntilDF80(ctx, fullUpdates.copyOfRange(start, start + BLOCK66_SIZE), 0x10)
            start += BLOCK66_SIZE
        }
        ctx = apply67eb94WithPendingRawAdapter(ctx)
        return finalize679f48ToSecondDF80(ctx)
    }

    fun deriveFrom679f48Inputs(previousBlocks: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray {
        val context = finalized679f48ContextFromInputs(previousBlocks, src4)
        return deriveFromFinalized679f48Context(context, offset, length)
    }

    fun deriveFrom660448RawDescriptor(rawDescriptorBlocks: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray {
        val previousBlocks = constructor670978Ptr28Blocks(rawDescriptorBlocks)
        return deriveFrom679f48Inputs(previousBlocks, src4, offset, length)
    }

    fun deriveFromFinalized679f48Context(context: ByteArray, offset: Int = 0, length: Int = 0x10): ByteArray {
        val t = Tables.shared
        val (src1, src2) = postDF80_67a960Inputs(context, t)
        return deriveFrom67a960Inputs(src1, src2, offset, length)
    }

    private fun deriveFrom67a960Inputs(src1: ByteArray, src2: ByteArray, offset: Int, length: Int): ByteArray {
        val t = Tables.shared
        val source67a978 = vm67cc18(0x1c0012000003b1cL, src1, src2, t)
        return deriveFrom67a978Source(source67a978, offset, length)
    }

    private fun deriveFrom67a978Source(source: ByteArray, offset: Int, length: Int): ByteArray {
        val t = Tables.shared
        val source67a990 = vm67cc18(0x82000000477L, source, source, t)
        return deriveFrom67a990Source(source67a990, offset, length)
    }

    private fun deriveFrom67a990Source(source: ByteArray, offset: Int, length: Int): ByteArray {
        val t = Tables.shared
        val window = vm67cc18(0x82000003c2dL, source, source, t)
        val chunks = final67cc18Sources(window, t)
        return deriveFrom67cc18Sources(chunks, offset, length)
    }

    private fun final67cc18Sources(window: ByteArray, t: Tables): ByteArray {
        val firstSrc = window.copyOfRange(0x40, 0x40 + BLOCK66_SIZE)
        val secondSrc = window.copyOfRange(0, BLOCK66_SIZE)
        return vm67cc18(0x420000054c3L, firstSrc, firstSrc, t) + vm67cc18(0x420000054c3L, secondSrc, secondSrc, t)
    }

    private fun deriveFrom67cc18Sources(sourceChunks: ByteArray, offset: Int, length: Int): ByteArray {
        val t = Tables.shared
        val encoded = ByteArray(sourceChunks.size)
        var start = 0
        while (start < sourceChunks.size) {
            val chunk = sourceChunks.copyOfRange(start, start + BLOCK66_SIZE)
            vm67cc18(0x420000059c9L, chunk, chunk, t).copyInto(encoded, start)
            start += BLOCK66_SIZE
        }
        return derive64de54Slice(encoded, offset, length)
    }

    private fun derive64de54Slice(encodedBlocks: ByteArray, offset: Int, length: Int): ByteArray {
        val t = Tables.shared
        val blocks = ArrayList<ByteArray>(encodedBlocks.size / BLOCK66_SIZE)
        var start = 0
        while (start < encodedBlocks.size) {
            blocks.add(encodedBlocks.copyOfRange(start, start + BLOCK66_SIZE)); start += BLOCK66_SIZE
        }
        if (blocks.isEmpty() && length > 0) throw FirstPairSliceException("empty source")
        val expanded = blocks.map { vm64e2b8(0x42000000106L, it, it, t) }

        val startBlock = offset shr 4
        val lowNibble = offset and 0x0f
        val outBlocks = (length + 0x0f) shr 4
        val out = ByteArray(outBlocks * BLOCK66_SIZE)
        for (outIndex in 0 until outBlocks) {
            val idx = startBlock + outIndex
            if (idx >= expanded.size) throw FirstPairSliceException("slice starts past source $idx")
            val scratchSrc2 = shiftedScratch(expanded[idx])
            val scratch: ByteArray = if (idx + 1 < expanded.size) {
                val src1 = expanded[idx + 1] + ByteArray(64)
                vm64e2b8(0x100042000000148L, src1, scratchSrc2, t)
            } else {
                vm64e2b8(0x82000000084L, scratchSrc2, scratchSrc2, t)
            }
            var shifted = scratch
            repeat(16 - lowNibble) { shifted = vm64e17c(shifted, shifted, t) }
            val stage = vm64e2b8(0x42000000000L, shifted, shifted, t)
            vm64e2b8(0x42000000042L, stage, stage, t).copyInto(out, outIndex * BLOCK66_SIZE)
        }
        return out
    }

    private fun postDF80_67a960Inputs(context: ByteArray, t: Tables): Pair<ByteArray, ByteArray> {
        val state = context.copyOfRange(0x114, 0x1a4)
        val buf3b0 = ByteArray(SCRATCH130_SIZE)
        val buf320 = ByteArray(SCRATCH130_SIZE)
        val buf3e = ByteArray(SCRATCH130_SIZE)
        val bufd = ByteArray(SCRATCH130_SIZE)

        buf3b0[15] = 6
        state.copyOfRange(0x5a, 0x6c).copyInto(buf3b0, 16)
        vm67cc18(0x40012000002ba3L, context.copyOfRange(0x180, 0x180 + 34), buf3b0.copyOfRange(0, 34), t).copyInto(buf3e, 0)

        buf320[15] = 6
        state.copyOfRange(0x36, 0x48).copyInto(buf320, 16)
        vm67cc18(0x4001200000255cL, context.copyOfRange(0x15c, 0x15c + 34), buf320.copyOfRange(0, 34), t).copyInto(buf3b0, 0x20)

        ByteArray(31).copyInto(buf3b0, 0)
        buf3b0[31] = 7
        vm67cc18(0x80022000000ca2L, buf3e.copyOfRange(0, 66), buf3b0.copyOfRange(0, 66), t).copyInto(bufd, 0)

        ByteArray(16).copyInto(buf3b0, 0)
        state.copyOfRange(0x12, 0x24).copyInto(buf3b0, 16)
        vm67cc18(0x40012000005f0eL, context.copyOfRange(0x138, 0x138 + 34), buf3b0.copyOfRange(0, 34), t).copyInto(buf3e, 0)

        ByteArray(31).copyInto(buf3b0, 0)
        buf3b0[31] = 7
        state.copyOfRange(0, 0x12).copyInto(buf3b0, 32)
        vm67cc18(0x400220000013d9L, buf3e.copyOfRange(0, 50), buf3b0.copyOfRange(0, 50), t).copyInto(buf320, 0x40)

        ByteArray(0x40).copyInto(buf320, 0)
        vm67cc18(0xc00420000016e9L, bufd.copyOfRange(0, 114), buf320.copyOfRange(0, 114), t).copyInto(buf3b0, 0x10)
        ByteArray(15).copyInto(buf3b0, 0)
        buf3b0[15] = 1

        val src1 = context.copyOfRange(0x192, 0x1a4) + ByteArray(112)
        return src1 to buf3b0
    }

    // ---- 64e2b8 VM family ----

    private fun vm64e2b8(magic: Long, src1: ByteArray, src2: ByteArray, t: Tables): ByteArray {
        val progOff = (magic and 0x3fffffL).toInt()
        val count = ((magic ushr 36) and 0x3fffL).toInt()
        val tail = (magic ushr 50).toInt()
        val total = count + tail
        val prog = checkedSlice(t.prog64e2b8, progOff, total)
        val out = ByteArray(total)
        var state = 0
        for (i in 0 until count) {
            state = step(state, src1[i].toInt() and 0xff, src2[i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        for (i in 0 until tail) {
            val pos = count + i
            state = step(state, null, src2[pos].toInt() and 0xff, prog[pos].toInt() and 0xff, t)
            out[pos] = (state and 7).toByte()
        }
        return out
    }

    private fun vm64e17c(src0: ByteArray, src1: ByteArray, t: Tables): ByteArray {
        var state = step(0, src0[0].toInt() and 0xff, src1[0].toInt() and 0xff, 14, t)
        val head = intArrayOf(22, 9, 33)
        for ((index, progByte) in head.withIndex()) {
            val pos = index + 1
            state = step(state, src0[pos].toInt() and 0xff, src1[pos].toInt() and 0xff, progByte, t)
        }
        val prog = checkedSlice(t.prog64e2b8, 0x1ce, 0x7e)
        val out = ByteArray(SCRATCH130_SIZE)
        for (i in 0 until 0x7e) {
            state = step(state, src0[4 + i].toInt() and 0xff, src1[4 + i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        val tailProg = intArrayOf(12, 17, 18, 27)
        for ((i, progByte) in tailProg.withIndex()) {
            val pos = 0x7e + i
            state = step(state, null, null, progByte, t)
            out[pos] = (state and 7).toByte()
        }
        return out
    }

    private fun shiftedScratch(block66: ByteArray): ByteArray {
        val scratch = ByteArray(SCRATCH130_SIZE)
        scratch[0x3f] = 3
        block66.copyInto(scratch, 0x40, 0, BLOCK66_SIZE)
        return scratch
    }

    // ---- LE helpers ----

    private fun readU32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun readU64LE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xff) shl (i * 8))
        return v
    }

    private fun writeU32LE(b: ByteArray, off: Int, value: Int) {
        for (i in 0 until 4) b[off + i] = ((value ushr (i * 8)) and 0xff).toByte()
    }

    private fun writeU64LE(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) b[off + i] = ((value ushr (i * 8)) and 0xff).toByte()
    }

    // ---- 679f48 spec arrays + constants ----

    private const val CONTEXT_679F48_SIZE = 0x20c
    private const val SCRATCH130_SIZE = 130

    // [magic, srcOffset, dstOffset]
    private val INIT_679F48_BLOCK18_SPECS = arrayOf(
        longArrayOf(0x120000058e3L, 0x42, 0x114),
        longArrayOf(0x12000004b6bL, 0x54, 0x126),
        longArrayOf(0x1200000388eL, 0x66, 0x138),
        longArrayOf(0x12000000662L, 0x78, 0x14a),
        longArrayOf(0x12000000c90L, 0x8a, 0x15c),
        longArrayOf(0x120000045f6L, 0x9c, 0x16e),
        longArrayOf(0x12000000139L, 0xae, 0x180),
        longArrayOf(0x12000002cb1L, 0xc0, 0x192),
    )

    private val AA8C_INITIAL_REDUCER_SPECS = arrayOf(
        longArrayOf(0x400120000030e8L, 0x114, 0x1ec),
        longArrayOf(0x40012000000006L, 0x126, 0x1f0),
        longArrayOf(0x400120000022d5L, 0x138, 0x1f4),
        longArrayOf(0x40012000001859L, 0x14a, 0x1f8),
        longArrayOf(0x40012000005e7aL, 0x15c, 0x1fc),
        longArrayOf(0x40012000004661L, 0x16e, 0x200),
        longArrayOf(0x4001200000178fL, 0x180, 0x204),
        longArrayOf(0x40012000002c8fL, 0x192, 0x208),
    )

    private val EB94_UPDATE_MAGICS = longArrayOf(
        0x12000004154L, 0x1200000392cL, 0x120000036dbL, 0x12000000f83L,
        0x120000000f9L, 0x12000000a72L, 0x120000018d7L, 0x1200000191dL,
    )

    // ---- 63c278 schedule (linear part: vectors → mix → tail → accum → bridge → prebranch) ----

    class Accum63c278(val sp440: ULongArray, val sp4f0: ULongArray, val sp5a0: ULongArray, val sp390: ULongArray)
    class Prebranch63c278(val sp390: UIntArray, val sp440: UIntArray, val sp6b0: UIntArray, val sp658: UIntArray)

    fun builder63c278InitialVectors(arg0: ByteArray, arg1: ByteArray): Pair<ULongArray, ULongArray> {
        val t = Tables.shared
        val x1 = ULongArray(44)
        for (i in 0 until V63) x1[i] = x1Word(rdU32(arg1, i * 4), i, t)
        for (i in 0 until V63) x1[V63 + i] = 0xb7059a553c133489uL
        val x0 = ULongArray(V63) { x0Word(rdU32(arg0, it * 4), it, t) }
        return x1 to x0
    }

    fun builder63c278SecondInitialVectors(arg0: ByteArray, arg2: ByteArray): Pair<ULongArray, ULongArray> {
        val t = Tables.shared
        val x2 = ULongArray(44)
        for (i in 0 until V63) x2[i] = x2Word(rdU32(arg2, i * 4), i, t)
        for (i in 0 until V63) x2[V63 + i] = 0x9a6e0b3eab651f3duL
        val x0 = ULongArray(V63) { x0BWord(rdU32(arg0, it * 4), it, t) }
        return x2 to x0
    }

    fun builder63c278ScalarMixVector(x1: ULongArray, x0: ULongArray, scalar: ULong): ULongArray {
        val t = Tables.shared
        val vec = x1.copyOf()
        val scalarMul = scalar * 0xc2f49ab55607d661uL + 0x5cd21b4822401581uL
        val scalarAdd = scalar * 0x31979e72b90f9217uL + 0x3a834f793d8d50d2uL
        var carry = vec[0]
        for (index in 0 until V63) {
            val (updateMul, laneAdd) = mixSeed(carry, scalarMul, scalarAdd, t)
            for (lane in 0 until V63) { val pos = index + lane; vec[pos] = vec[pos] + laneAdd + x0[lane] * updateMul }
            carry = nextCarry(vec[index], vec[index + 1], t)
            vec[index + 1] = carry
        }
        return vec
    }

    fun builder63c278ScalarMix2Vector(x2: ULongArray, x0: ULongArray, scalar: ULong): ULongArray {
        val t = Tables.shared
        val vec = x2.copyOf()
        val scalarMul = scalar * 0xd499812ba25ee663uL + 0x261ebe70f821cbc3uL
        val scalarAdd = scalar * 0xb1af6fa1cb6e1d69uL + 0xbfe73a2bd6da82dcuL
        var carry = vec[0]
        for (index in 0 until V63) {
            val (updateMul, laneAdd) = mix2Seed(carry, scalarMul, scalarAdd, t)
            for (lane in 0 until V63) { val pos = index + lane; vec[pos] = vec[pos] + laneAdd + x0[lane] * updateMul }
            carry = nextCarry2(vec[index], vec[index + 1], t)
            vec[index + 1] = carry
        }
        return vec
    }

    fun builder63c278Tail1U32Words(mixed: ULongArray): UIntArray {
        val t = Tables.shared
        var carry = 0x57078c52164039c3uL
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            carry *= 0xea79f5006ed1ed3duL
            carry += mixed[V63 + index] * 0x66df92deb399335buL
            carry += 0x09c9f7e39169d6f1uL
            val folded = fold63c278(carry * 0x4a61801334a2066buL + 0x346cdb9fa10bc247uL, 0x3019f0, 7, t)
            val word = (carry.toUInt().toULong() * 0x6d8d9d63uL + folded.toUInt().toULong() * 0x70000000uL + 0xc780a908uL).toUInt()
            val foldedTail = fold63c278(folded, 0x3019f0, 9, t)
            carry = folded * 0xe3d2a03f1bfe297fuL + foldedTail * 0x401d681000000000uL + 0x7b8480dbcf98c453uL
            val to = (index and 7) * 4
            val mul = u32Tbl(0x123448 + to, t); val add = u32Tbl(0x123468 + to, t)
            out[index] = (word.toULong() * mul.toULong() + add.toULong()).toUInt()
        }
        return out
    }

    fun builder63c278Tail2U32Words(mixed: ULongArray): UIntArray {
        val t = Tables.shared
        var carry = 0x7b98879460aee9e2uL
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            carry *= 0xf65fd3833526aa13uL
            carry += mixed[V63 + index] * 0x806aa29ec1ed1481uL
            carry += 0xb4f29f8797e744b7uL
            val folded = fold63c278(carry * 0xa05b2cf659a43c93uL + 0x48da81dd905ece62uL, 0x301cf0, 7, t)
            val word = (carry.toUInt().toULong() * 0x0080b9a9uL + folded.toUInt().toULong() * 0xd0000000uL + 0xde4d224euL).toUInt()
            val foldedTail = fold63c278(folded, 0x301cf0, 9, t)
            carry = folded * 0x3fc1e03941c67b59uL + foldedTail * 0xe3984a7000000000uL + 0x05975fb8f5057bb2uL
            val to = (index and 7) * 4
            val mul = u32Tbl(0x112628 + to, t); val add = u32Tbl(0x121928 + to, t)
            out[index] = (word.toULong() * mul.toULong() + add.toULong()).toUInt()
        }
        return out
    }

    fun builder63c278AccumulatorStreams(arg2: ByteArray, tail2: UIntArray): Accum63c278 {
        val t = Tables.shared
        val sp5a0 = ULongArray(V63); val sp440 = ULongArray(V63)
        var runningA = 0uL
        for (index in 0 until V63) {
            val item = accumAWord(rdU32(arg2, index * 4), index, t)
            sp5a0[index] = item; runningA += item; sp440[index] = runningA
        }
        val sp4f0 = ULongArray(V63); val sp390 = ULongArray(V63)
        var runningB = 0uL
        for (index in 0 until V63) {
            val item = accumBWord(tail2[index], index, t)
            sp4f0[index] = item; runningB += item; sp390[index] = runningB
        }
        return Accum63c278(sp440, sp4f0, sp5a0, sp390)
    }

    fun builder63c278BridgeConvolutionVector(a: Accum63c278): ULongArray {
        val out = ULongArray(44)
        for (index in 0 until 44) {
            val low = maxOf(index - 21, 0)
            val high = minOf(index, 21)
            val mixed: ULong
            if (low > 21) {
                mixed = 0x67bdf132221fb4e9uL
            } else {
                val start = index - high
                var dot = 0uL
                for (pos in start..high) dot += a.sp4f0[index - pos] * a.sp5a0[pos]
                var spanA = a.sp440[high]
                val spanB: ULong
                if (index >= 22) {
                    spanA -= a.sp440[low - 1]
                    spanB = a.sp390[index - low] - a.sp390[index - high - 1]
                } else {
                    spanB = a.sp390[index - low]
                }
                mixed = 0x67bdf132221fb4e9uL +
                    (high - low + 1).toULong() * 0x1593d040a4114154uL +
                    dot * 0x2edc06a97199e3efuL +
                    spanA * 0x0557cced2c1cc47euL +
                    spanB * 0xc1edf977b66f09cauL
            }
            out[index] = mixed * 0xb3bd694c1c94d1a7uL + 0x2c0585771e81c36auL
        }
        return out
    }

    fun builder63c278BridgeX0Vector(arg0: ByteArray): ULongArray {
        val t = Tables.shared
        return ULongArray(V63) { bridgeX0Word(rdU32(arg0, it * 4), it, t) }
    }

    fun builder63c278BridgeMixVector(sp230: ULongArray, x0: ULongArray, scalar: ULong): ULongArray {
        val t = Tables.shared
        val vec = sp230.copyOf()
        val scalarMul = scalar * 0x5bcfc2db5b41aa8buL + 0xb0be584b9c560cebuL
        val scalarAdd = scalar * 0x7cb9da0648140cfduL + 0xcb165f95963e265buL
        var carry = vec[0]
        for (index in 0 until V63) {
            val (updateMul, laneAdd) = bridgeMixSeed(carry, scalarMul, scalarAdd, t)
            for (lane in 0 until V63) { val pos = index + lane; vec[pos] = vec[pos] + laneAdd + x0[lane] * updateMul }
            carry = bridgeNextCarry(vec[index], vec[index + 1], t)
            vec[index + 1] = carry
        }
        return vec
    }

    fun builder63c278BridgeSP128Words(sp230: ULongArray): UIntArray {
        val t = Tables.shared
        var carry = 0x18541ef2e5658ac6uL
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            carry *= 0x590b8c9bda7aa7a5uL
            carry += sp230[V63 + index] * 0x93e68b973b124f01uL
            carry += 0x0357d31d6340b07auL
            val folded = fold63c278(carry * 0x052e2e9b238ffd17uL + 0x7a84d77fc047bb5cuL, 0x302070, 7, t)
            val word = (carry.toUInt().toULong() * 0xbca742b5uL + folded.toUInt().toULong() * 0xd0000000uL + 0x74c20619uL).toUInt()
            val foldedTail = fold63c278(folded, 0x302070, 9, t)
            carry = folded * 0x9d12b2b955ef375buL + foldedTail * 0xa10c8a5000000000uL + 0x5831e87503aab765uL
            val to = (index and 7) * 4
            val mul = u32Tbl(0x118568 + to, t); val add = u32Tbl(0x1234a8 + to, t)
            out[index] = (word.toULong() * mul.toULong() + add.toULong()).toUInt()
        }
        return out
    }

    fun builder63c278PrebranchInitialStreams(arg0: ByteArray, tail1: UIntArray, sp128: UIntArray): Prebranch63c278 {
        val t = Tables.shared
        val sp390 = UIntArray(V63); val sp440 = UIntArray(V63); val sp6b0 = UIntArray(V63); val sp658 = UIntArray(V63)
        for (index in 0 until V63) {
            val to = (index and 7) * 4
            sp390[index] = foldTbl(0x3020f0 + index * 4, t)
            sp440[index] = u32TblAffine(tail1[index], 0x122228 + to, 0x11dd08 + to, t)
            sp6b0[index] = u32TblAffine(sp128[index], 0x1234c8 + to, 0x11b428 + to, t)
            sp658[index] = u32TblAffine(rdU32(arg0, index * 4), 0x114968 + to, 0x11fda8 + to, t)
        }
        return Prebranch63c278(sp390, sp440, sp6b0, sp658)
    }

    fun builder63c278PrebranchSP4F0Words(arg0: ByteArray): UIntArray {
        val t = Tables.shared
        val arg0Words = UIntArray(V63) { rdU32(arg0, it * 4) }
        val first = (arg0Words[0].toULong() * 0x7193fc77uL + 0x318e9b49uL).toUInt()
        var state = prebranchSP4F0State(first, t)
        var selected = prebranchSP4F0FoldState(state, t)
        var carry = (state.toULong() * 0x8e834ce3uL + (selected shl 31).toULong() + 0x0afac599uL).toUInt()
        val out = ArrayList<UInt>(V63)
        var index = 0
        while (true) {
            if (index == V63 - 1) {
                val storeValue = (carry.toULong() * 0x3f277405uL + 0xa0c1d6f4uL).toUInt()
                val to = (index * 4) and 0x1c
                out.add(u32TblAffine(storeValue, 0x122248 + to, 0x1172a8 + to, t)); break
            }
            val nextIndex = index + 1
            val nto = (nextIndex and 7) * 4
            val value = u32TblAffine(arg0Words[nextIndex], 0x11c3e8 + nto, 0x11b448 + nto, t)
            carry = (carry.toULong() * 0x3f277405uL + value.toULong() * 0xa8000000uL).toUInt()
            state = prebranchSP4F0State(value, t)
            selected = prebranchSP4F0FoldState(state, t)
            val word = (state.toULong() * 0x8e834ce3uL + (selected shl 31).toULong() + 0x0afac599uL).toUInt()
            carry = (word.toULong() * 0xb0000000uL + carry.toULong()).toUInt()
            val storeValue = carry + 0xc8c1d6f4u
            val to = (index * 4) and 0x1c
            out.add(u32TblAffine(storeValue, 0x122248 + to, 0x1172a8 + to, t))
            carry = word
            index = nextIndex
        }
        return out.toUIntArray()
    }

    fun builder63c278PrebranchSP230Words(sp4f0: UIntArray): UIntArray {
        val t = Tables.shared
        val q0 = UIntArray(4) { u32Tbl(0x125f20 + it * 4, t) }
        val q1 = UIntArray(4) { u32Tbl(0x125f30 + it * 4, t) }
        val out = UIntArray(V63)
        val seed = (q0.toList() + q1.toList() + q0.toList() + q1.toList() + q0.toList() + q1.toList().take(2))
        for (i in 0 until V63) out[i] = seed[i]
        for (index in 0 until V63) {
            val to = (index and 7) * 4
            val mul = u32Tbl(0x11c408 + to, t)
            out[index] = (sp4f0[index].toULong() * mul.toULong() + out[index].toULong()).toUInt()
        }
        for (index in 0 until V63) {
            val to = (index and 7) * 4
            val staticWord = foldTbl(0x302148 + index * 4, t)
            val mul = u32Tbl(0x118588 + to, t)
            out[index] = (staticWord.toULong() * mul.toULong() + out[index].toULong()).toUInt()
        }
        return out
    }

    fun builder63c278PrebranchSP5A0Words(sp230: UIntArray): UIntArray {
        val t = Tables.shared
        var carry = 0xa7964b7du
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            val word = sp230[index]
            carry = (carry.toULong() * 0x856c3a53uL + word.toULong()).toUInt()
            var folded = (carry.toULong() * 0x287caef9uL + 0x0ac0f465uL).toUInt()
            folded = fold32ByNibbles63c278(folded, 0x302238, 7, t)
            val foldedTail = foldTbl(0x302238 + (folded and 0x0fu).toInt() * 4, t) + (folded shr 4)
            val nextPart = (carry.toULong() * 0xd8018ba1uL + folded.toULong() * 0x70000000uL + 0x63f7e16auL).toUInt()
            val tail = (folded.toULong() * 0x20718073uL + foldedTail.toULong() * 0xf8e7f8d0uL).toUInt()
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(nextPart, 0x11a028 + to, 0x115f48 + to, t)
            carry = tail + 0xe056c4a1u
        }
        return out
    }

    // ---- 63c278 leaf helpers ----

    private fun x1Word(word: UInt, index: Int, t: Tables): ULong {
        val w0 = u32Affine(word, index, 0x115488, 0x121908, t)
        val w = w0 * 0x30316c9du + 0xe533e221u
        var folded = w.toULong() * 0x74ddf8a53c239debuL + 0xc98ef94d2aa6d2f9uL
        folded = fold63c278(folded, 0x301770, 8, t) * 0xff444fcf00000000uL
        return w.toULong() * 0xdda5a8135a0bc9fbuL + folded + 0x8031c96ed30bf85euL
    }

    private fun x0Word(word: UInt, index: Int, t: Tables): ULong {
        val w0 = u32Affine(word, index, 0x11a9a8, 0x122aa8, t)
        val w = w0 * 0x707fe555u + 0x1d759ee3u
        var folded = w.toULong() * 0xc7e623dc4156435duL + 0xa7268272249650e4uL
        folded = fold63c278(folded, 0x3017f0, 8, t) * 0xd70f3ef300000000uL
        return w.toULong() * 0x1defa278095a88b9uL + folded + 0xc8066dafe659e3dduL
    }

    private fun x2Word(word: UInt, index: Int, t: Tables): ULong {
        val w0 = u32Affine(word, index, 0x11f588, 0x123488, t)
        val w = w0 * 0xe41d161fu + 0xb12fcee1u
        var folded = w.toULong() * 0xf3402af2c5c78103uL + 0x81b5a02882be6230uL
        folded = fold63c278(folded, 0x301a70, 8, t) * 0xc69af5ab00000000uL
        return w.toULong() * 0x057da4120776f3ffuL + folded + 0x7d7d6bb0e7cd07d3uL
    }

    private fun x0BWord(word: UInt, index: Int, t: Tables): ULong {
        val w0 = u32Affine(word, index, 0x118f28, 0x118548, t)
        val w = w0 * 0x4dce977fu + 0x7275db64u
        var folded = w.toULong() * 0x3125dbf4f55c0c6duL + 0x1167036e8591663cuL
        folded = fold63c278(folded, 0x301af0, 8, t) * 0xee1902df00000000uL
        return w.toULong() * 0x41108caa0013530duL + folded + 0x2ce8cc914f903207uL
    }

    private fun mixSeed(carry: ULong, scalarMul: ULong, scalarAdd: ULong, t: Tables): Pair<ULong, ULong> {
        val mixed = carry * scalarMul + scalarAdd
        var folded = mixed * 0xe56ee0d2dabe3103uL + 0xe1a57f65c01b39acuL
        folded = fold63c278(folded, 0x301870, 7, t) * 0x43cf3bc9b0000000uL
        val seed = mixed * 0x47b2ca50a9011f2fuL + folded + 0xa9ccf36f06c69525uL
        return (seed * 0x707f1c911d72472duL + 0x20d7bce79675ce2euL) to (seed * 0x62d17dd555b3e7b5uL + 0xa95e929c3eca7e5euL)
    }

    private fun nextCarry(updatedFirst: ULong, updatedSecond: ULong, t: Tables): ULong {
        var folded = updatedFirst * 0x500a38540d22b25buL + 0xae9b83bb74900f1euL
        folded = fold63c278(folded, 0x3018f0, 7, t)
        val carryMix = folded * 0x6e12b4b0721da33buL + 0x15fb45ff71081e4euL
        var folded2 = carryMix * 0xb926d0a2f88df903uL + 0x931eca912f88a4c7uL
        folded2 = fold63c278(folded2, 0x301970, 9, t) * 0x30cbc3f000000000uL
        val mixed2 = carryMix * 0x025241c2cd0d8443uL + folded2
        return mixed2 * 0x8074fb50d5400883uL + updatedSecond + 0x9a2a45734b3e5fb0uL
    }

    private fun mix2Seed(carry: ULong, scalarMul: ULong, scalarAdd: ULong, t: Tables): Pair<ULong, ULong> {
        val mixed = carry * scalarMul + scalarAdd
        var folded = mixed * 0x126e65dcb0b83de1uL + 0x5454202b530d9481uL
        folded = fold63c278(folded, 0x301b70, 7, t) * 0x3d2ffccf90000000uL
        val seed = mixed * 0x4c89449a165d8427uL + folded + 0x654ba76b767a427cuL
        return (seed * 0x564d78f55b5eefabuL + 0xf24aa781d14548f5uL) to (seed * 0xeb7cfc7c768d163cuL + 0x09afd4171a0c7a44uL)
    }

    private fun nextCarry2(updatedFirst: ULong, updatedSecond: ULong, t: Tables): ULong {
        var folded = updatedFirst * 0xbca4dd7019310b05uL + 0x088f442397943c2auL
        folded = fold63c278(folded, 0x301bf0, 7, t)
        val carryMix = folded * 0x727c48215454885buL + 0xcaf590adfa7e603buL
        var folded2 = carryMix * 0xfb3565409b501139uL + 0x74b39dd74e3ac2eduL
        folded2 = fold63c278(folded2, 0x301c70, 9, t) * 0x0081c49000000000uL
        val mixed2 = carryMix * 0xf4d598a4fa80dabfuL + folded2
        return mixed2 * 0x0d5f48e79ddef1c9uL + updatedSecond + 0x9506d95873fe6ec8uL
    }

    private fun accumAWord(word: UInt, index: Int, t: Tables): ULong {
        val w0 = u32Affine(word, index, 0x11a9c8, 0x120f28, t)
        val w = w0 * 0x2545ee53u + 0xf74fe193u
        var folded = w.toULong() * 0x69289ee9a98801f5uL + 0x89bfbb0b1b21e854uL
        folded = fold63c278(folded, 0x301d70, 8, t)
        return w.toULong() * 0x12f7e0136d4dad87uL + folded * 0x9917c7f500000000uL + 0xf80d0f670554b0a4uL
    }

    private fun accumBWord(word: UInt, index: Int, t: Tables): ULong {
        val w0 = u32Affine(word, index, 0x11bbe8, 0x121948, t)
        val w = w0 * 0x7dd1ecf7u + 0xdc8c9daeu
        var folded = w.toULong() * 0xf13beb213918d361uL + 0xa1220647c9883100uL
        folded = fold63c278(folded, 0x301df0, 8, t)
        return w.toULong() * 0x952e2be9091d60c7uL + folded * 0xd89eb2d900000000uL + 0x54e3b2cc004948beuL
    }

    private fun bridgeX0Word(word: UInt, index: Int, t: Tables): ULong {
        val w0 = u32Affine(word, index, 0x11cd88, 0x112e48, t)
        val w = w0 * 0x1c8f15cfu + 0x05d1107bu
        var folded = w.toULong() * 0x19d189b1be9d480buL + 0xd2bafb34c1909b26uL
        folded = fold63c278(folded, 0x301e70, 8, t) * 0x2ece929d00000000uL
        return w.toULong() * 0x7529d4f2739a8b41uL + folded + 0xdb7158ce45fcb750uL
    }

    private fun bridgeMixSeed(carry: ULong, scalarMul: ULong, scalarAdd: ULong, t: Tables): Pair<ULong, ULong> {
        val mixed = carry * scalarMul + scalarAdd
        var folded = mixed * 0x34af0af1bbce60dduL + 0x61b88589a4883d43uL
        folded = fold63c278(folded, 0x301ef0, 7, t) * 0x2da4669430000000uL
        val seed = mixed * 0x8f5055af84d40129uL + folded + 0x7bf63147a7179819uL
        return (seed * 0xdb5bb72dd36c07a9uL + 0x155c3f0a68fbcfe1uL) to (seed * 0x0ee832c1be220ab1uL + 0xcc246f1fe68886a9uL)
    }

    private fun bridgeNextCarry(updatedFirst: ULong, updatedSecond: ULong, t: Tables): ULong {
        var folded = updatedFirst * 0x060c229ff67c02fbuL + 0xab8d83e0c2b70611uL
        folded = fold63c278(folded, 0x301f70, 7, t)
        val carryMix = folded * 0x6a605d1236fbedd7uL + 0xfd36e0ea31dbe67cuL
        var folded2 = carryMix * 0x57a20a77f75734e1uL + 0x4cc594baeecf3ecauL
        folded2 = fold63c278(folded2, 0x301ff0, 9, t) * 0x9c0c689000000000uL
        val mixed2 = carryMix * 0x1e0bc5b08daead97uL + folded2
        return mixed2 * 0x93bd22efcdeacdc3uL + updatedSecond + 0xc175492c1e8124acuL
    }

    private fun prebranchSP4F0FoldState(state: UInt, t: Tables): UInt {
        var folded = (state.toULong() * 0x3dbef531uL + 0x554aacd3uL).toUInt()
        folded = fold32ByNibbles63c278(folded, 0x3021f8, 7, t)
        val selected = u32Tbl(0x122ac8 + (folded and 7u).toInt() * 4, t)
        return selected + (folded shr 3)
    }

    private fun prebranchSP4F0State(word: UInt, t: Tables): UInt {
        val half = word shr 1
        val bitTable = u32Tbl(0x126850 + (word and 1u).toInt() * 4, t)
        return (half.toULong() * 0x0c949fdbuL + bitTable.toULong()).toUInt()
    }

    // ---- 63c278 table primitives ----

    private fun u32Affine(word: UInt, index: Int, mulTable: Int, addTable: Int, t: Tables): UInt {
        val to = (index * 4) and 0x1c
        val mul = u32Tbl(mulTable + to, t); val add = u32Tbl(addTable + to, t)
        return (word.toULong() * mul.toULong() + add.toULong()).toUInt()
    }

    private fun u32TblAffine(word: UInt, mulTable: Int, addTable: Int, t: Tables): UInt {
        val mul = u32Tbl(mulTable, t); val add = u32Tbl(addTable, t)
        return (word.toULong() * mul.toULong() + add.toULong()).toUInt()
    }

    private fun u32Tbl(absoluteOffset: Int, t: Tables): UInt {
        val rel = absoluteOffset - 0x112588
        if (rel < 0 || rel + 4 > t.u32Tables63c278.size) throw FirstPairSliceException("63c278 u32 OOB $absoluteOffset")
        return rdU32(t.u32Tables63c278, rel)
    }

    private fun foldTbl(absoluteOffset: Int, t: Tables): UInt {
        val rel = absoluteOffset - 0x2feb18
        if (rel < 0 || rel + 4 > t.foldTables63c278.size) throw FirstPairSliceException("63c278 fold OOB $absoluteOffset")
        return rdU32(t.foldTables63c278, rel)
    }

    private fun fold63c278(value: ULong, tableOffset: Int, rounds: Int, t: Tables): ULong {
        var folded = value
        repeat(rounds) {
            val rel = tableOffset - 0x2feb18 + (folded and 0x0fuL).toInt() * 8
            folded = rdU64(t.foldTables63c278, rel) + (folded shr 4)
        }
        return folded
    }

    private fun fold32ByNibbles63c278(value: UInt, tableOffset: Int, rounds: Int, t: Tables): UInt {
        var folded = value
        repeat(rounds) {
            val word = foldTbl(tableOffset + (folded and 0x0fu).toInt() * 4, t)
            folded = word + (folded shr 4)
        }
        return folded
    }

    private fun rdU32(b: ByteArray, off: Int): UInt =
        (b[off].toUInt() and 0xffu) or ((b[off + 1].toUInt() and 0xffu) shl 8) or
            ((b[off + 2].toUInt() and 0xffu) shl 16) or ((b[off + 3].toUInt() and 0xffu) shl 24)

    private fun rdU64(b: ByteArray, off: Int): ULong {
        var v = 0uL
        for (i in 0 until 8) v = v or ((b[off + i].toULong() and 0xffuL) shl (i * 8))
        return v
    }

    private const val V63 = 22

    // ---- 64c524 (caller-row primitive) ----

    fun builder64c524Arg0U64Words(arg0: ByteArray): ULongArray {
        val t = Tables.shared
        val out = ULongArray(V63)
        for (index in 0 until V63) {
            val affine = u32Affine(rdU32(arg0, index * 4), index, 0x118e88, 0x118428, t)
            val word = affine * 0xc0134d17u + 0x71ee3738u
            val folded = fold63c278(word.toULong() * 0x30f8c406f090e325uL + 0x7a3d4622dcb83626uL, 0x2fffb0, 8, t)
            out[index] = word.toULong() * 0x430cd374007356b5uL + folded * 0xc0efe7af00000000uL + 0xe5faf13619f0e974uL
        }
        return out
    }

    fun builder64c524WorkspaceAfterUpdate(arg0U64Words: ULongArray, scalar: ULong, x2Workspace: ByteArray): ByteArray {
        val t = Tables.shared
        val x2 = ULongArray(44) { rdU64(x2Workspace, it * 8) }
        var carryWord = x2[0]
        for (base in 0 until V63) {
            val (mul, broadcast) = c524WorkspaceParams(scalar, carryWord, t)
            for (offset in 0 until V63) { val pos = base + offset; x2[pos] = x2[pos] + broadcast + arg0U64Words[offset] * mul }
            carryWord = c524RewriteSecondWord(x2[base], x2[base + 1], t)
            x2[base + 1] = carryWord
        }
        return packU64LE(x2)
    }

    fun builder64c524FinalU32Words(x2Workspace: ByteArray): UIntArray {
        val t = Tables.shared
        val x2 = ULongArray(44) { rdU64(x2Workspace, it * 8) }
        var carry = 0xa231ae9017976cb8uL
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            val tailWord = x2[V63 + index]
            val foldedInput = carry * 0xcd03684f066c56f1uL + tailWord * 0x5691aa6f378a40d3uL + 0x1c0f700d822da380uL
            val ff = c524FinalFirstFold(foldedInput, t)
            val foldedFull = fold63c278(ff.folded, 0x3001b0, 8, t)
            val nextCarry = ff.nextBase * 0xe9b2139140497c53uL + foldedFull * 0xfb683ad000000000uL
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(ff.side, 0x120ec8 + to, 0x11b3a8 + to, t)
            carry = nextCarry + 0xee51a1bedb406a0duL
        }
        return out
    }

    fun builder64c524OutputWords(arg0: ByteArray, scalar: ULong, x2Workspace: ByteArray): UIntArray {
        val words = builder64c524Arg0U64Words(arg0)
        val updated = builder64c524WorkspaceAfterUpdate(words, scalar, x2Workspace)
        return builder64c524FinalU32Words(updated)
    }

    private class FinalFold(val nextBase: ULong, val side: UInt, val folded: ULong)

    private fun c524WorkspaceParams(scalar: ULong, firstX2Word: ULong, t: Tables): Pair<ULong, ULong> {
        val seedA = scalar * 0x37225d56e2d37ae5uL + 0x3d01a097518f54bcuL
        val seedB = scalar * 0xd03e88ab453ae68buL + 0x5ca5b123c7ddda97uL
        var mixed = seedA * firstX2Word + seedB
        var folded = mixed * 0x4355499b9de8f281uL + 0xd616e418c4bc0066uL
        folded = fold63c278(folded, 0x300030, 7, t)
        mixed = mixed * 0x65dd922c973ea261uL + folded * 0xb2b8c001f0000000uL + 0x457dafb763ad58f5uL
        return (mixed * 0x9fdf9fbdfb76ed77uL + 0x14646b0029e6e968uL) to (mixed * 0x55fb9010c9586c69uL + 0x63292bd5dae78b98uL)
    }

    private fun c524RewriteSecondWord(first: ULong, second: ULong, t: Tables): ULong {
        var folded = first * 0xe191957128574e8duL + 0xca735f7e01db7229uL
        folded = fold63c278(folded, 0x3000b0, 7, t)
        var mixed = folded * 0xc0759fa984b4b32buL + 0x4e00c393f4ad1417uL
        var folded2 = mixed * 0x2ca78080bf929d71uL + 0x489c8bdec6559298uL
        folded2 = fold63c278(folded2, 0x300130, 9, t)
        mixed = mixed * 0x21a1db1ac0ca1e41uL + folded2 * 0x3659a2f000000000uL
        return mixed * 0x1685e929cba4a88fuL + second + 0x56570c70d17acce1uL
    }

    private fun c524FinalFirstFold(value: ULong, t: Tables): FinalFold {
        val product = value * 0x2d5e3aab4210238buL
        var side = value.toUInt() * 0x6773f057u
        var folded = foldTbl64(0x3001b0 + (product and 0x0fuL).toInt() * 8, t) + ((product + 0x04d30efa28ce4180uL) shr 4)
        folded = fold63c278(folded, 0x3001b0, 6, t)
        side += folded.toUInt() * 0xb0000000u
        val nextBase = folded
        folded = fold63c278(folded, 0x3001b0, 1, t)
        return FinalFold(nextBase, side + 0xc5960ad5u, folded)
    }

    private fun foldTbl64(absoluteOffset: Int, t: Tables): ULong {
        val rel = absoluteOffset - 0x2feb18
        if (rel < 0 || rel + 8 > t.foldTables63c278.size) throw FirstPairSliceException("63c278 fold64 OOB $absoluteOffset")
        return rdU64(t.foldTables63c278, rel)
    }

    // ---- 64cd40 (caller-row primitive, sibling of 64c524/64bd0c) ----

    fun builder64cd40Arg0U64Words(arg0: ByteArray): ULongArray {
        val t = Tables.shared
        val out = ULongArray(V63)
        for (index in 0 until V63) {
            val affine = u32Affine(rdU32(arg0, index * 4), index, 0x123e08, 0x123428, t)
            val word = affine * 0x8c2231afu + 0x6d97daf4u
            val folded = fold63c278(word.toULong() * 0xeb7e2c45742037f1uL + 0x3363481bcd8bcd54uL, 0x3010f0, 8, t)
            out[index] = word.toULong() * 0x92d397b84a615e45uL + folded * 0x73db406b00000000uL + 0xbdc443456d026c77uL
        }
        return out
    }

    fun builder64cd40WorkspaceAfterUpdate(arg0U64Words: ULongArray, scalar: ULong, x2Workspace: ByteArray): ByteArray {
        val t = Tables.shared
        val x2 = ULongArray(44) { rdU64(x2Workspace, it * 8) }
        var carryWord = x2[0]
        for (base in 0 until V63) {
            val (mul, broadcast) = cd40WorkspaceParams(scalar, carryWord, t)
            for (offset in 0 until V63) { val pos = base + offset; x2[pos] = x2[pos] + broadcast + arg0U64Words[offset] * mul }
            carryWord = cd40RewriteSecondWord(x2[base], x2[base + 1], t)
            x2[base + 1] = carryWord
        }
        return packU64LE(x2)
    }

    fun builder64cd40FinalU32Words(x2Workspace: ByteArray): UIntArray {
        val t = Tables.shared
        val x2 = ULongArray(44) { rdU64(x2Workspace, it * 8) }
        var carry = 0x0b784750d9181757uL
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            val tailWord = x2[V63 + index]
            val foldedInput = carry * 0xcaaf4f9d292a519duL + tailWord * 0x28d4341977190ea5uL + 0x593214d4b8068287uL
            val ff = cd40FinalFirstFold(foldedInput, t)
            val foldedFull = fold63c278(ff.folded, 0x3012f0, 8, t)
            val nextCarry = ff.nextBase * 0xda8179ca5c614737uL + foldedFull * 0x39eb8c9000000000uL
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(ff.side, 0x11c3a8 + to, 0x11dce8 + to, t)
            carry = nextCarry + 0xd2a88419cf931098uL
        }
        return out
    }

    fun builder64cd40OutputWords(arg0: ByteArray, scalar: ULong, x2Workspace: ByteArray): UIntArray {
        val words = builder64cd40Arg0U64Words(arg0)
        val updated = builder64cd40WorkspaceAfterUpdate(words, scalar, x2Workspace)
        return builder64cd40FinalU32Words(updated)
    }

    private fun cd40WorkspaceParams(scalar: ULong, firstX2Word: ULong, t: Tables): Pair<ULong, ULong> {
        val seedA = scalar * 0x697d0ecbc60d5d0fuL + 0x937857c2eed8d2b4uL
        val seedB = scalar * 0xbc235eb940a876dduL + 0x18b363a938b968b2uL
        var mixed = seedA * firstX2Word + seedB
        var folded = mixed * 0x10adb81e27dd69a7uL + 0xe7f726c1fe72a787uL
        folded = fold63c278(folded, 0x301170, 7, t)
        mixed = mixed * 0x411310f58c3cbf15uL + folded * 0xc72468f1d0000000uL + 0x9d21e3104874d274uL
        return (mixed * 0x0b7281fc87cf2277uL + 0x719b343dac285e92uL) to (mixed * 0x71031cfa7b36346euL + 0x0c7018d77bac9e24uL)
    }

    private fun cd40RewriteSecondWord(first: ULong, second: ULong, t: Tables): ULong {
        var folded = first * 0x4fec946356180ba9uL + 0xcd7ac1129eab2dd8uL
        folded = fold63c278(folded, 0x3011f0, 7, t)
        var mixed = folded * 0x1eb04e8030fffbd7uL + 0xaee6470479c51db3uL
        var folded2 = mixed * 0x8d796f74dc90608duL + 0x9ac49f51ec349615uL
        folded2 = fold63c278(folded2, 0x301270, 9, t)
        mixed = mixed * 0xf9e1d6a0ce988cf5uL + folded2 * 0x1cc37f7000000000uL
        return mixed * 0x87f365d52d3aa373uL + second + 0x41b4561d4c674238uL
    }

    private fun cd40FinalFirstFold(value: ULong, t: Tables): FinalFold {
        val product = value * 0x947905173900b973uL
        var side = value.toUInt() * 0x8f376f21u
        var folded = foldTbl64(0x3012f0 + (product and 0x0fuL).toInt() * 8, t) + ((product + 0x0c4fdbc2f625a640uL) shr 4)
        folded = fold63c278(folded, 0x3012f0, 6, t)
        side += folded.toUInt() * 0x50000000u
        val nextBase = folded
        folded = fold63c278(folded, 0x3012f0, 1, t)
        return FinalFold(nextBase, side + 0x06ae2bd7u, folded)
    }

    private fun packU64LE(words: ULongArray): ByteArray {
        val out = ByteArray(words.size * 8)
        for (i in words.indices) { var v = words[i]; for (k in 0 until 8) { out[i * 8 + k] = (v and 0xffuL).toByte(); v = v shr 8 } }
        return out
    }

    // ---- 64bd0c primitive (642f60 core engine) ----

    fun builder64bd0cArg0U64Words(arg0: ByteArray): ULongArray {
        val t = Tables.shared
        val out = ULongArray(V63)
        for (index in 0 until V63) {
            val affine = u32Affine(rdU32(arg0, index * 4), index, 0x11c328, 0x113648, t)
            val word = affine * 0x3e251f3fu + 0xc80f68f4u
            val folded = fold63c278(word.toULong() * 0xf636dda3668409f3uL + 0xa1898a9b9b0c347buL, 0x2ff030, 8, t)
            out[index] = word.toULong() * 0x57c9f2b4caac6659uL + folded * 0xa43bca7d00000000uL + 0x6de2d7b43700ac09uL
        }
        return out
    }

    fun builder64bd0cWorkspaceAfterUpdate(arg0U64Words: ULongArray, scalar: ULong, x2Workspace: ByteArray): ByteArray {
        val t = Tables.shared
        val x2 = ULongArray(44) { rdU64(x2Workspace, it * 8) }
        var carryWord = x2[0]
        for (base in 0 until V63) {
            val (mul, broadcast) = bd0cWorkspaceParams(scalar, carryWord, t)
            for (offset in 0 until V63) { val pos = base + offset; x2[pos] = x2[pos] + broadcast + arg0U64Words[offset] * mul }
            carryWord = bd0cRewriteSecondWord(x2[base], x2[base + 1], t)
            x2[base + 1] = carryWord
        }
        return packU64LE(x2)
    }

    fun builder64bd0cFinalU32Words(x2Workspace: ByteArray): UIntArray {
        val t = Tables.shared
        val x2 = ULongArray(44) { rdU64(x2Workspace, it * 8) }
        var carry = 0xa8100bf8a7268389uL
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            val tailWord = x2[V63 + index]
            val mixed = carry * 0xdc6110b4d93c58f7uL + tailWord * 0x29221b50b5648139uL
            val foldedInput = mixed + 0x02ea5a475ff009a0uL
            val ff = bd0cFinalFirstFold(foldedInput, t)
            val foldedFull = fold63c278(ff.folded, 0x2ff230, 8, t)
            val nextCarry = ff.nextBase * 0x1323954bb9644419uL + foldedFull * 0x69bbbe7000000000uL
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(ff.side, 0x11b328 + to, 0x115348 + to, t)
            carry = nextCarry + 0x7a8f00bf503f94fbuL
        }
        return out
    }

    fun builder64bd0cOutputWords(arg0: ByteArray, scalar: ULong, x2Workspace: ByteArray): UIntArray =
        builder64bd0cFinalU32Words(builder64bd0cWorkspaceAfterUpdate(builder64bd0cArg0U64Words(arg0), scalar, x2Workspace))

    private fun bd0cWorkspaceParams(scalar: ULong, firstX2Word: ULong, t: Tables): Pair<ULong, ULong> {
        val seedA = scalar * 0x9cbd06d772de1901uL + 0x34e214bca24f560cuL
        val seedB = scalar * 0xbe4812554b30ebf8uL + 0xc770490f6d646597uL
        var mixed = seedA * firstX2Word + seedB
        var folded = mixed * 0x213ec1d8d1bc2d9buL + 0x3cda12a384db6d3buL
        folded = fold63c278(folded, 0x2ff0b0, 7, t)
        mixed = mixed * 0x91ab7a47a981923buL + folded * 0x0ed61381f0000000uL + 0xab62fec0d215095buL
        return (mixed * 0x8493b5edc5e368a1uL + 0x6f8e182c75ab0bb8uL) to (mixed * 0x2698148ddd26a50euL + 0x740f3b32b62a7210uL)
    }

    private fun bd0cRewriteSecondWord(first: ULong, second: ULong, t: Tables): ULong {
        var folded = first * 0xe121bd3e759b23f3uL + 0x8c105c11c96e758buL
        folded = fold63c278(folded, 0x2ff130, 7, t)
        var mixed = folded * 0x4afc5649aee85307uL + 0xdc13fe8d315ad1a7uL
        var folded2 = mixed * 0x7c64ef86eb0d2547uL + 0x95549ebb3b944abeuL
        folded2 = fold63c278(folded2, 0x2ff1b0, 9, t)
        mixed = mixed * 0xd9ef08a678eb7ba3uL + folded2 * 0x8e62b3b000000000uL
        return mixed * 0x3352cbd4c2b4f2efuL + second + 0x793cd011929995d8uL
    }

    private fun bd0cFinalFirstFold(value: ULong, t: Tables): FinalFold {
        var folded = value * 0xbfeaa39c4f3a2fdfuL + 0xb07328e69628c835uL
        var side = value.toUInt() * 0x48daeaa5u
        folded = fold63c278(folded, 0x2ff230, 7, t)
        side += folded.toUInt() * 0x50000000u
        val nextBase = folded
        folded = fold63c278(folded, 0x2ff230, 1, t)
        return FinalFold(nextBase, side + 0xdfea4892u, folded)
    }

    // ---- 642f60 affine stages SP2A8 / SP1F8 ----

    fun builder642f60StageSP2A8WordsFromX1(x1: ByteArray): UIntArray {
        val t = Tables.shared
        var state = 0x373c5287u
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            val to = (index * 4) and 0x1c
            val mixed = u32TblAffine(rdU32(x1, index * 4), 0x116808 + to, 0x113f48 + to, t) * 0x3c4be1d6u
            state = state * 0x92c1f72bu + mixed + 0xb77cdf91u
            var folded = state * 0x52c0ee2fu + 0xaec98dccu
            val sideBase = state * 0x58fd5601u
            folded = fold32ByNibbles63c278(folded, 0x2feef0, 7, t)
            val side = sideBase + (folded shl 28) + 0x79c97500u
            val folded8 = fold32ByNibbles63c278(folded, 0x2feef0, 1, t)
            out[index] = u32TblAffine(side, 0x11b2e8 + to, 0x11e468 + to, t)
            state = folded * 0x01d6d2edu + folded8 * 0xe292d130u + 0x57678c8fu
        }
        return out
    }

    fun builder642f60StageSP1F8WordsFromX0(x0: ByteArray): UIntArray {
        val t = Tables.shared
        var state = 0x27b40eb7u
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            val to = (index * 4) and 0x1c
            val mixed = u32TblAffine(rdU32(x0, index * 4), 0x122168 + to, 0x1171c8 + to, t) * 0x8c0bfb6eu
            state = state * 0xcfb36435u + mixed + 0x11d2681du
            var folded = state * 0xc337e20fu + 0x69960635u
            folded = fold32ByNibbles63c278(folded, 0x2ff2b0, 7, t)
            val side = state * 0x37e76a4du + folded * 0xd0000000u + 0x0a2a2ce9u
            val folded8 = fold32ByNibbles63c278(folded, 0x2ff2b0, 1, t)
            out[index] = u32TblAffine(side, 0x112d68 + to, 0x115368 + to, t)
            state = folded * 0x01e08913u + folded8 * 0xe1f76ed0u + 0xe153bedeu
        }
        return out
    }

    // ---- 642f60 64bd0c workspaces (convolution) + SP300/250 stages ----

    fun builder642f60StageSP300WordsFrom64bd0cOutput(output: ByteArray): UIntArray =
        affineWordsFrom64bd0cOutput(output, 0x114808, 0x112d48)

    fun builder642f60StageSP250WordsFrom64bd0cOutput(output: ByteArray): UIntArray =
        affineWordsFrom64bd0cOutput(output, 0x118e28, 0x11b368)

    fun builder642f60StageSP148WordsFrom64bd0cOutput(output: ByteArray): UIntArray =
        affineWordsFrom64bd0cOutput(output, 0x113668, 0x113f68)

    fun builder642f60StageSPF0WordsFrom64bd0cOutput(output: ByteArray): UIntArray =
        affineWordsFrom64bd0cOutput(output, 0x116828, 0x1233c8)

    fun builder642f60StageSP1A0WordsFrom64bd0cOutput(output: ByteArray): UIntArray =
        affineWordsFrom64bd0cOutput(output, 0x117ac8, 0x120568)

    private fun affineWordsFrom64bd0cOutput(output: ByteArray, mulTable: Int, addTable: Int): UIntArray {
        val t = Tables.shared
        return UIntArray(V63) { u32Affine(rdU32(output, it * 4), it, mulTable, addTable, t) }
    }

    fun builder642f60First64bd0cWorkspaceFromX1(x1Source: ByteArray, sp2a8: UIntArray): ByteArray {
        val t = Tables.shared
        val a = ULongArray(V63) { firstAWord(rdU32(x1Source, it * 4), it, t) }
        val b = ULongArray(V63) { firstBWord(sp2a8[it], it, t) }
        return convolutionWorkspaceU64(a, b, 0xc69bed71f29f125auL, 0x90f419f6ac783668uL, 0x4cb8f06bf0049b7duL, 0x0f7eac37b6812618uL, 0xd1388a4d4ecb84f3uL, 0xdacc0c3ac7084aaduL, 0x094d3bfe92d4e136uL)
    }

    fun builder642f60Second64bd0cWorkspace(sp1f8: UIntArray, sp300: UIntArray): ByteArray {
        val t = Tables.shared
        val a = ULongArray(V63) { secondAWord(sp1f8[it], it, t) }
        val b = ULongArray(V63) { secondBWord(sp300[it], it, t) }
        return convolutionWorkspaceU64(a, b, 0x65de471500bb3121uL, 0x5b751607ca3bf450uL, 0xf90d1f20daf847f7uL, 0x3bd2bf8830ac06c7uL, 0x8510f1581a89dd50uL, 0x95f22cc42a8e1323uL, 0x54b290ac63e72185uL)
    }

    fun builder642f60Third64bd0cWorkspaceFromX2(x2Source: ByteArray): ByteArray {
        val t = Tables.shared
        val src = UIntArray(V63) { rdU32(x2Source, it * 4) }
        val a = ULongArray(V63) { thirdAWord(src[it], it, t) }
        val b = ULongArray(V63) { thirdBWord(src[it], it, t) }
        return convolutionWorkspaceU64(a, b, 0x5b1e432b74fd20f9uL, 0xd6a9de8138afb1c4uL, 0x491764cf27f996a7uL, 0x5f259b9e6d3d894fuL, 0x0634d81d5a7a1464uL, 0x81b9bc3ed86899dbuL, 0x7f3bdcb4320a4605uL)
    }

    private fun convolutionWorkspaceU64(
        a: ULongArray, b: ULongArray, baseAdd: ULong, countMul: ULong, productMul: ULong,
        sumAMul: ULong, sumBMul: ULong, finalMul: ULong, finalAdd: ULong,
    ): ByteArray {
        val aPrefix = prefixSumsU64(a)
        val bPrefix = prefixSumsU64(b)
        val out = ULongArray(44)
        for (index in 0 until 44) {
            val start = maxOf(0, index - 21)
            val end = minOf(index, 21)
            if (start > end) { out[index] = baseAdd * finalMul + finalAdd; continue }
            var productSum = 0uL
            for (pos in start..end) productSum += a[pos] * b[index - pos]
            val sumA = rangeSumFromPrefix(aPrefix, start, end)
            val sumB = rangeSumFromPrefix(bPrefix, index - end, index - start)
            val mixed = (end - start + 1).toULong() * countMul + baseAdd + productSum * productMul + sumA * sumAMul + sumB * sumBMul
            out[index] = mixed * finalMul + finalAdd
        }
        return packU64LE(out)
    }

    private fun prefixSumsU64(words: ULongArray): ULongArray {
        var total = 0uL
        return ULongArray(words.size) { total += words[it]; total }
    }

    private fun rangeSumFromPrefix(prefix: ULongArray, start: Int, end: Int): ULong {
        if (start > end) return 0uL
        val total = prefix[end]
        return if (start == 0) total else total - prefix[start - 1]
    }

    // 642f60 A/B per-stream word transforms
    private fun firstAWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x7a6bdb55u + 0x6f457678u else u32Affine(word, index, 0x11b308, 0x120e68, t) * 0x9935dc8fu + 0x8faec549u
        val folded = fold63c278(w.toULong() * 0x62170eaa882a1aaduL + 0xfcded5c74336bb62uL, 0x2fef30, 8, t)
        return w.toULong() * 0x1ae027ac75efae5duL + folded * 0x6a59778f00000000uL + 0x272a0fcbb9692010uL
    }

    private fun firstBWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x8a0c43a1u + 0xe1069988u else u32Affine(word, index, 0x117a88, 0x11c308, t) * 0x8fce17f9u + 0x9aa95d9cu
        val folded = fold63c278(w.toULong() * 0x91c0e3def121255duL + 0x50be110705349aeauL, 0x2fefb0, 8, t)
        return w.toULong() * 0x861960b1d03ace7fuL + folded * 0x7f5bb67500000000uL + 0x4a2faf413913b4a2uL
    }

    private fun secondAWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0xcce32bdbu + 0x79dae932u else u32Affine(word, index, 0x115388, 0x1153a8, t) * 0x7bd77a89u + 0x0d07fa2au
        val folded = fold63c278(w.toULong() * 0x0a0b1df06a7b196duL + 0xfd9f62e38b4829f7uL, 0x2ff2f0, 8, t)
        return w.toULong() * 0xb1bf8eba2d4b2a69uL + folded * 0xb6b0ac9300000000uL + 0x381faa6c090fdcd8uL
    }

    private fun secondBWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x9affbc41u + 0x96a579e0u else u32Affine(word, index, 0x11dc48, 0x11b348, t) * 0x3749a60du + 0xb803d34cu
        val folded = fold63c278(w.toULong() * 0xa5ffca145f08d59buL + 0x8ce0b7edd5a16ba2uL, 0x2ff370, 8, t)
        return w.toULong() * 0xabe6b5e1333dcc8fuL + folded * 0xfbd091e300000000uL + 0x1df38d76faeb4eaduL
    }

    private fun thirdAWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x92a36947u + 0xab2632fcu else u32Affine(word, index, 0x117aa8, 0x11a8c8, t) * 0xe77cb783u + 0x000f1f23u
        val folded = fold63c278FirstNibbleBeforeAdd(w.toULong() * 0xaf7f459e89cfb7e5uL, 0x5c6139b5f80c5a20uL, 0x2ff3f0, t)
        return w.toULong() * 0x796b8710218eb0c5uL + folded * 0x7224389f00000000uL + 0x086af43c0726c3a9uL
    }

    private fun thirdBWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x032a79c5u + 0x8da26e96u else u32Affine(word, index, 0x11e488, 0x11e4a8, t) * 0xb13f4189u + 0x2b7b0e41u
        val folded = fold63c278(w.toULong() * 0x3144f0ff41a1df83uL + 0x3d414cbf18310011uL, 0x2ff470, 8, t)
        return w.toULong() * 0x861a1f875b2dc69buL + folded * 0xf0b786f700000000uL + 0x63b83ae085557472uL
    }

    private fun fold63c278FirstNibbleBeforeAdd(product: ULong, addend: ULong, tableOffset: Int, t: Tables): ULong {
        val rel = tableOffset - 0x2feb18 + (product and 0x0fuL).toInt() * 8
        val folded = rdU64(t.foldTables63c278, rel) + ((product + addend) shr 4)
        return fold63c278(folded, tableOffset, 7, t)
    }

    // ---- 6473d0 (output preimages for caller rows) — staged via 64c524 ----

    fun builder6473d0FirstStreamsFromIn2(in2: ByteArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            streamWord6473(rdU32(in2, it * 4), it, 0x121868, 0x1221a8, 0x77de3399u, 0x58e2f220u, 0x4455c2a7u, 0x5fb4bcbau, 0x1a5dd9d49d616e49uL, 0x35ca2ed01cc6fc5euL, 0x2ffeb0, 0x52a77526a5e20393uL, 0xc0342d0500000000uL, 0xe18c4214f69259e3uL, t)
        }
        val b = ULongArray(V63) {
            streamWord6473(rdU32(in2, it * 4), it, 0x120588, 0x121888, 0x87742383u, 0xc9c817bbu, 0x10aa86fbu, 0xc396dd66u, 0xb113a5da27ca9531uL, 0x9ebadbc5621b3655uL, 0x2fff30, 0xc109dc395415ce8fuL, 0x05cb504100000000uL, 0xf36dd79d8bc15312uL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0First64c524Workspace(in2: ByteArray): ByteArray {
        val s = builder6473d0FirstStreamsFromIn2(in2)
        return convolutionWorkspaceU64(s.a, s.b, 0xea076efa672179abuL, 0x901a2a1a1970b75fuL, 0x4300ab4afadcbbf7uL, 0x7de2bd685f4f4709uL, 0xa38b46b3853abda1uL, 0xd77dcf630e2637dbuL, 0x5c2daa5720071908uL)
    }

    fun builder6473d0SP488WordsFrom64c524Output(output: ByteArray): UIntArray =
        affineWordsFrom64bd0cOutput(output, 0x11f548, 0x120ee8)

    /** Generic 6473d0/642f60 stream word: index-0 has its own u32 mul/add. */
    private fun streamWord6473(
        word: UInt, index: Int, mulTable: Int, addTable: Int, u0Mul: UInt, u0Add: UInt, uMul: UInt, uAdd: UInt,
        foldMul: ULong, foldAdd: ULong, foldTable: Int, linearMul: ULong, foldedMul: ULong, linearAdd: ULong, t: Tables,
    ): ULong {
        val w = if (index == 0) word * u0Mul + u0Add else u32Affine(word, index, mulTable, addTable, t) * uMul + uAdd
        val folded = fold63c278(w.toULong() * foldMul + foldAdd, foldTable, 8, t)
        return w.toULong() * linearMul + folded * foldedMul + linearAdd
    }

    // 6473d0 Second (out0 seed + sp488)
    fun builder6473d0SecondStreams(out0Seed: ByteArray, sp488Words: UIntArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            streamWord6473(rdU32(out0Seed, it * 4), it, 0x1196c8, 0x114888, 0x39489dd3u, 0x84fea303u, 0x55b224dbu, 0x42db73c8u, 0x9ab586ead7a10a23uL, 0x745539fbd1dd9f3duL, 0x300230, 0x8522c616d05ac3d1uL, 0xcf9ca88500000000uL, 0xf16d077d4bce4ca7uL, t)
        }
        val b = ULongArray(V63) {
            streamWord6473(sp488Words[it], it, 0x11b3c8, 0x118ea8, 0xce63ced3u, 0x34652b7au, 0xed7fd02du, 0xcaf62b51u, 0x9345f0e11be71ab7uL, 0xc397557b35344783uL, 0x3002b0, 0xfa2624af5708736duL, 0x4e61310500000000uL, 0x8ace523ec06aa294uL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0Second64c524Workspace(out0Seed: ByteArray, sp488Words: UIntArray): ByteArray {
        val s = builder6473d0SecondStreams(out0Seed, sp488Words)
        return convolutionWorkspaceU64(s.a, s.b, 0x3a938197b6fc0c6duL, 0x6b6072f5b68541eduL, 0x17273c8d47cdf1cduL, 0xbe760fcc7bdf8909uL, 0x0a87db0f0661d8c1uL, 0x4de8dcb2e8bedd8duL, 0x24bff8d093bcfa48uL)
    }

    // 6473d0 Third (source words from second output + context + in0, SP430, streams from in2)
    fun builder6473d0ThirdSourceWords(secondOutput: ByteArray, contextSource: ByteArray, in0: ByteArray): UIntArray {
        val t = Tables.shared
        val base = sourceBase6473d0Third
        return UIntArray(V63) { index ->
            val to = (index * 4) and 0x1c
            var word = base[index] + rdU32(secondOutput, index * 4) * u32Tbl(0x115ec8 + to, t)
            word += rdU32(contextSource, 0x208 + index * 4) * u32Tbl(0x11ebe8 + to, t)
            word + rdU32(in0, index * 4) * u32Tbl(0x1196e8 + to, t)
        }
    }

    fun builder6473d0ThirdSP430Words(sourceWords: UIntArray): UIntArray =
        affineFoldStage(sourceWords, 0xcf789378u, 0x807fabb9u, 0xa5cb8299u, 0x35afa3e6u, 0x300330, 0x87693409u, 0xf0000000u, 0xd6091d59u, 0x67448d71u, 0x8bb728f0u, 0x4b56addbu, 0x112da8, 0x123da8, false)

    fun builder6473d0ThirdStreams(in2: ByteArray, sp488Words: UIntArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            streamWord6473(rdU32(in2, it * 4), it, 0x11d468, 0x1218a8, 0xa0c3f4e7u, 0xca140f35u, 0xd85895b7u, 0xfe4ad573u, 0x2d873af1d6d51fabuL, 0xfa32d6ea7762f713uL, 0x300370, 0x7c054da63351e573uL, 0xd60956a700000000uL, 0x8b3f83af9a986f82uL, t)
        }
        val b = ULongArray(V63) {
            streamWord6473(sp488Words[it], it, 0x117b08, 0x112dc8, 0x2f4895c9u, 0x548370bfu, 0x5f18e243u, 0x601ea383u, 0xccb971c8df4a10cbuL, 0xbdc12d7c86b96bc9uL, 0x3003f0, 0x8ae83bc3470143dduL, 0x8970cf0900000000uL, 0x888ef8790921d76buL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0Third64c524Workspace(in2: ByteArray, sp488Words: UIntArray): ByteArray {
        val s = builder6473d0ThirdStreams(in2, sp488Words)
        return convolutionWorkspaceU64(s.a, s.b, 0x917278c29688d77buL, 0xc7906210d3d1e095uL, 0x4701e4eecdc47ad3uL, 0xaa41cc7914d43151uL, 0x4ed4ffc26ea9e41fuL, 0xddb691aa5df690b3uL, 0xa100d744fa1a1050uL)
    }

    // 6473d0 Fourth (out1 seed + third output)
    fun builder6473d0FourthStreams(out1Seed: ByteArray, thirdOutput: ByteArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            streamWord6473(rdU32(out1Seed, it * 4), it, 0x11e508, 0x11dc88, 0x984ca5a3u, 0x3bb4166du, 0xb2d6e3f9u, 0xfb7f4287u, 0xd1ffa13328840637uL, 0x2262d6944a6e7f87uL, 0x300470, 0x814152cd7b468effuL, 0xac42268700000000uL, 0xef9b4475a4d654dauL, t)
        }
        val b = ULongArray(V63) {
            streamWord6473(rdU32(thirdOutput, it * 4), it, 0x11bb88, 0x11bba8, 0xe701693fu, 0xf6342272u, 0xc6f77079u, 0xe9127329u, 0xd34530d23ec1313buL, 0xb004febcdc86f0eduL, 0x3004f0, 0xceecbc048b18d1c5uL, 0xdc92270100000000uL, 0xc37fd29022b6d9f5uL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0Fourth64c524Workspace(out1Seed: ByteArray, thirdOutput: ByteArray): ByteArray {
        val s = builder6473d0FourthStreams(out1Seed, thirdOutput)
        return convolutionWorkspaceU64(s.a, s.b, 0xae9114e278083c51uL, 0x4fe0792a5595a26cuL, 0x803778f9280a64a7uL, 0xe524e08d7cfc960fuL, 0x21c7bf84b2e3e84cuL, 0x46c1c6f2d742d5b5uL, 0x1f5c231ea84be10cuL)
    }

    // 6473d0 Fifth (source words from fourth output + context + in1, SP3D8, streams from sp430)
    fun builder6473d0FifthSourceWords(fourthOutput: ByteArray, contextSource: ByteArray, in1: ByteArray): UIntArray {
        val t = Tables.shared
        val base = sourceBase6473d0Fifth
        return UIntArray(V63) { index ->
            val to = (index * 4) and 0x1c
            var word = base[index] + rdU32(fourthOutput, index * 4) * u32Tbl(0x1148a8 + to, t)
            word += rdU32(contextSource, 0x158 + index * 4) * u32Tbl(0x11e528 + to, t)
            word + rdU32(in1, index * 4) * u32Tbl(0x1148c8 + to, t)
        }
    }

    fun builder6473d0FifthSP3D8Words(sourceWords: UIntArray): UIntArray =
        affineFoldStage(sourceWords, 0x68c6dea6u, 0x3e3c7069u, 0xe2d8f8a1u, 0xcb837279u, 0x300570, 0x3f4a34bbu, 0x50000000u, 0xd3af772cu, 0x09ce4439u, 0x631bbc70u, 0x4949bbb6u, 0x123dc8, 0x115408, false)

    fun builder6473d0FifthStreams(sp430Words: UIntArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            streamWord6473(sp430Words[it], it, 0x11f568, 0x11fd88, 0x72f7749fu, 0xf4acd75fu, 0x496b3517u, 0xcb6c6440u, 0xed35bde10b7f0a6duL, 0x6dc10d7393e30fd4uL, 0x3005b0, 0x35078add682583b3uL, 0x2ff00d6100000000uL, 0x98856e9773f37314uL, t)
        }
        val b = ULongArray(V63) {
            streamWord6473(sp430Words[it], it, 0x1148e8, 0x118ec8, 0x37d64dabu, 0xc5e5e2fbu, 0xbfc55b8fu, 0x1239d41du, 0xb32c7a78268d803fuL, 0x3751011be1daba47uL, 0x300630, 0x769635acb20a2d3duL, 0x5c54cc7d00000000uL, 0xd7e17822568f72b6uL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0Fifth64c524Workspace(sp430Words: UIntArray): ByteArray {
        val s = builder6473d0FifthStreams(sp430Words)
        return convolutionWorkspaceU64(s.a, s.b, 0x6bb3ed620e4062d1uL, 0x5a7ef60c532941a8uL, 0x6d7ef3b93c27bb1buL, 0x4899963aacd79adeuL, 0xb234bdac100e1d64uL, 0x7c7297a28fd61e6buL, 0xc0f5768719fc1ff6uL)
    }

    // 6473d0 Sixth (SP380 from fifth output; SP750/SP698 cross-keyed streams)
    fun builder6473d0SixthSP380Words(fifthOutput: ByteArray): UIntArray {
        val t = Tables.shared
        return UIntArray(V63) { u32Affine(rdU32(fifthOutput, it * 4), it, 0x1205a8, 0x119f88, t) }
    }

    fun builder6473d0SixthStreams(sp430Words: UIntArray, sp380Words: UIntArray): Strm {
        val t = Tables.shared
        // SP750: index-0 from B0, body from A table/u32, fold+linear from B
        val a = ULongArray(V63) {
            streamWord6473(sp380Words[it], it, 0x112de8, 0x120f08, 0x84509d71u, 0x1c1eb057u, 0xe1c1f541u, 0xa44e1c99u, 0x601f255bbc8594afuL, 0x3abe7da8622c20aauL, 0x3006b0, 0x1a4af047ce7b1291uL, 0xa22de34100000000uL, 0xfc749709a3c0a2f0uL, t)
        }
        // SP698: index-0 from A0, body from B table/u32, fold+linear from A
        val b = ULongArray(V63) {
            streamWord6473(sp430Words[it], it, 0x113fa8, 0x122a28, 0xaab2b12bu, 0x7657d9e0u, 0xeaf35335u, 0x0aa66a43u, 0x14ba61e7dca94a8buL, 0x50b1b5306d47040buL, 0x300730, 0xd41818fe79de8a5buL, 0xb5f6b68f00000000uL, 0xd151dec0329e6e93uL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0Sixth64c524Workspace(sp430Words: UIntArray, sp380Words: UIntArray): ByteArray {
        val s = builder6473d0SixthStreams(sp430Words, sp380Words)
        return convolutionWorkspaceU64(s.a, s.b, 0x48627c9e905863c1uL, 0x870c05f8b8020220uL, 0x171538f14356cf37uL, 0x077d4a1b0a412afauL, 0xe17b173ed4c11f30uL, 0xcfb213eb0fd9778fuL, 0xad771cda5ed87b82uL)
    }

    // 6473d0 Seventh (SP328 from sixth output; SP750 from in0, SP698 from sp380)
    fun builder6473d0SeventhSP328Words(sixthOutput: ByteArray): UIntArray {
        val t = Tables.shared
        return UIntArray(V63) { u32Affine(rdU32(sixthOutput, it * 4), it, 0x116888, 0x115ee8, t) }
    }

    fun builder6473d0SeventhStreams(in0: ByteArray, sp380Words: UIntArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            streamWord6473(rdU32(in0, it * 4), it, 0x11a928, 0x1205c8, 0xcbc32467u, 0x1cbc099bu, 0xd5de3091u, 0xc850f622u, 0x29ccd0b4b66bf69buL, 0x92b5c9aad779cd01uL, 0x3007b0, 0xf8eecd0e379e94dduL, 0x69bd621900000000uL, 0x42ed620b1951ea95uL, t)
        }
        val b = ULongArray(V63) {
            streamWord6473(sp380Words[it], it, 0x1221c8, 0x1136c8, 0xa331630fu, 0x9bfc072bu, 0x5c6a96efu, 0x7bbe0d20u, 0x0f9465160d0a8253uL, 0xf56e8b822124be55uL, 0x300830, 0x0889f295bda63f59uL, 0x5f21e5dd00000000uL, 0x827f2e3ed1537188uL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0Seventh64c524Workspace(in0: ByteArray, sp380Words: UIntArray): ByteArray {
        val s = builder6473d0SeventhStreams(in0, sp380Words)
        return convolutionWorkspaceU64(s.a, s.b, 0x4f71889106fdcf1auL, 0xc645bd197ffec780uL, 0x902bc054731b0e19uL, 0x6ee76be95cd58d16uL, 0x2975699057393140uL, 0xfabebc0148fce955uL, 0xd9e959c072d37dafuL)
    }

    // 6473d0 Eighth (SP2D0 from seventh output; A/B streams from sp3d8)
    fun builder6473d0EighthSP2D0Words(seventhOutput: ByteArray): UIntArray {
        val t = Tables.shared
        return UIntArray(V63) { u32Affine(rdU32(seventhOutput, it * 4), it, 0x122a48, 0x117208, t) }
    }

    fun builder6473d0EighthStreams(sp3d8Words: UIntArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            streamWord6473(sp3d8Words[it], it, 0x118448, 0x114908, 0x06cb3af9u, 0x35bb0129u, 0xac8612f9u, 0x93df82d0u, 0xea864d2bbd71e693uL, 0xbd72f04e9eb16893uL, 0x3008b0, 0xfc50406703b9351buL, 0x89c4bba700000000uL, 0xdc2e9343b6f35309uL, t)
        }
        val b = ULongArray(V63) {
            streamWord6473(sp3d8Words[it], it, 0x1136e8, 0x123de8, 0x744d2f57u, 0x54be6d45u, 0xed2a4af5u, 0x7159ddd6u, 0xf5a291e49092493duL, 0x317bb3a8544de11fuL, 0x300930, 0x4de23df1210f0f8fuL, 0x33652f4500000000uL, 0xc716615aa5d2b4a0uL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0Eighth64c524Workspace(sp3d8Words: UIntArray): ByteArray {
        val s = builder6473d0EighthStreams(sp3d8Words)
        return convolutionWorkspaceU64(s.a, s.b, 0x6b3d1044d1e0bae4uL, 0x1cfde242e61ced64uL, 0x9c4b2fa9d6a82bcbuL, 0x397a076065abdda4uL, 0xd82dc30e2ff5c69buL, 0xfc34a2ab410fb0abuL, 0x750634486b3a5505uL)
    }

    // 6473d0 Ninth — multi-stage (3 source reducers + 2 convolution reducers + workspace)
    fun builder6473d0NinthFirstSourceWords(eighthOutput: ByteArray, contextSource: ByteArray, sp328Words: UIntArray, sp2d0Words: UIntArray): UIntArray {
        val t = Tables.shared
        val base = sourceBase6473d0NinthFirst
        return UIntArray(V63) { index ->
            val to = (index * 4) and 0x1c
            val sp2d0Delta = sp2d0Words[index] * u32Tbl(0x117b28 + to, t)
            base[index] +
                rdU32(eighthOutput, index * 4) * u32Tbl(0x11d488 + to, t) +
                rdU32(contextSource, 0x208 + index * 4) * u32Tbl(0x113fc8 + to, t) +
                sp328Words[index] * u32Tbl(0x112e08 + to, t) +
                sp2d0Delta + sp2d0Delta
        }
    }

    fun builder6473d0NinthOut2Words(sourceWords: UIntArray): UIntArray =
        affineFoldStage(sourceWords, 0x6c423344u, 0x5f7e4217u, 0x7b015015u, 0x5f6e7aceu, 0x3009b0, 0xb4d76dfbu, 0x10000000u, 0x9eeaec8fu, 0x9b8c81cbu, 0x4737e350u, 0xd38a5bb7u, 0x119fa8, 0x11ec08, false)

    fun builder6473d0NinthSecondSourceWords(sp2d0Words: UIntArray, contextSource: ByteArray, out2Words: UIntArray): UIntArray {
        val t = Tables.shared
        val base = sourceBase6473d0NinthSecond
        return UIntArray(V63) { index ->
            val to = (index * 4) and 0x1c
            base[index] +
                sp2d0Words[index] * u32Tbl(0x113fe8 + to, t) +
                rdU32(contextSource, 0x260 + index * 4) * u32Tbl(0x117228 + to, t) +
                out2Words[index] * u32Tbl(0x11b3e8 + to, t)
        }
    }

    fun builder6473d0NinthSP278Words(sourceWords: UIntArray): UIntArray =
        affineFoldStage(sourceWords, 0x8d141c95u, 0xb65c329fu, 0x76178341u, 0xda083f12u, 0x3009f0, 0x7da69527u, 0x90000000u, 0x01f661edu, 0x4f3ae49fu, 0x0c51b610u, 0xc23b392au, 0x1125c8, 0x118468, false)

    fun builder6473d0NinthFirstStreams(sp3d8Words: UIntArray, sp278Words: UIntArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            u64StreamWord(sp3d8Words[it], it, 0x119fc8, 0x11a948, 0x3f3b07b5u, 0xa76f4d34u, 0x42a5684e40c837e5uL, 0x8187dd38d660ab02uL, 0x300a30, 0x41edfb94e441a439uL, 0xc387f23b00000000uL, 0x8630a437323b9a06uL, t)
        }
        val b = ULongArray(V63) {
            u64StreamWord(sp278Words[it], it, 0x114008, 0x115428, 0x3bc03ba1u, 0x551e0b18u, 0x4a4ebd73a2ac7ecfuL, 0xc26c352e790eb9d2uL, 0x300ab0, 0x3b1bfc266fb46b3buL, 0x325b382b00000000uL, 0xc26ae547d56e3752uL, t)
        }
        return Strm(a, b, shiftedPrefixSumsU64(a), shiftedPrefixSumsU64(b))
    }

    fun builder6473d0NinthSP1C8Words(aWords: ULongArray, bWords: ULongArray): UIntArray =
        convolutionReducerU32Words63c278(
            aWords, bWords, 0x44890e57fee2b127uL, 0x22e7e2dd39986f0cuL, 0x3579615d7809d6f9uL, 0xefcd57be2939dd8euL, 0xfb335b5dda0d71fauL,
            0xa56af869b8f14f9fuL, 0x4dce076dc16d62a6uL, 0x300b30, 0x5c05a20fu, 0xf0000000u, 0x42d490fbu,
            0x3422cdbd16480c5fuL, 0x9b7f3a1000000000uL, 0x5788d57815189c66uL, 0x11e548, 0x119fe8,
        )

    fun builder6473d0NinthSecondStreams(in1: ByteArray, sp328Words: UIntArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            u64StreamWord(rdU32(in1, it * 4), it, 0x115f08, 0x118488, 0xa23b4591u, 0x27b4f995u, 0xe3d571fe10b7dd6fuL, 0x0ead98380f165a1buL, 0x300bb0, 0xb919418f2dce08c1uL, 0x24883b3100000000uL, 0xc178a260ab561c2duL, t)
        }
        val b = ULongArray(V63) {
            u64StreamWord(sp328Words[it], it, 0x11c348, 0x11e568, 0xc7e7ff39u, 0x6ede9d60u, 0xb7f69af6de331b05uL, 0xfb07ecba9e561d89uL, 0x300c30, 0xc2cb60ecc908be59uL, 0x9c0181bb00000000uL, 0xf63168d72b49d4d5uL, t)
        }
        return Strm(a, b, shiftedPrefixSumsU64(a), shiftedPrefixSumsU64(b))
    }

    fun builder6473d0NinthSP118Words(aWords: ULongArray, bWords: ULongArray): UIntArray =
        convolutionReducerU32Words63c278(
            aWords, bWords, 0xc46e4bf2bc94905fuL, 0xb43becaf979eccdbuL, 0x0286e00ddaf23867uL, 0xea769816201b6855uL, 0xa5fd2d47569ca4a9uL,
            0x131a43f6b293c469uL, 0xae11e8f737712446uL, 0x300cb0, 0xe9b78f65u, 0x30000000u, 0x3d19005du,
            0xd206f236cb1e0bd9uL, 0x4e1f427000000000uL, 0x1008f89662553eaauL, 0x11d4a8, 0x114028,
        )

    fun builder6473d0NinthThirdSourceWords(sp1c8Words: UIntArray, contextSource: ByteArray, sp118Words: UIntArray): UIntArray {
        val t = Tables.shared
        return UIntArray(44) { index ->
            val to = (index * 4) and 0x1c
            u32Tbl(0x114048 + (index and 7) * 4, t) +
                sp1c8Words[index] * u32Tbl(0x117b48 + to, t) +
                rdU32(contextSource, 0x2b8 + index * 4) * u32Tbl(0x1184a8 + to, t) +
                sp118Words[index] * u32Tbl(0x1168a8 + to, t)
        }
    }

    fun builder6473d0NinthSP68Words(sourceWords: UIntArray): UIntArray =
        affineFoldStage(sourceWords, 0xa2d2a851u, 0x9b30705fu, 0x6cbeb92du, 0x82af87fcu, 0x300d30, 0x72482a2fu, 0x50000000u, 0xc925d16au, 0x1d589f7bu, 0x2a760850u, 0xa10a4f8bu, 0x118ee8, 0x11c368, false)

    fun builder6473d0Ninth64c524Workspace(sp68Words: UIntArray): ByteArray {
        val t = Tables.shared
        val words = ULongArray(44) {
            u64StreamWord(sp68Words[it], it, 0x117248, 0x1168c8, 0xddfc952fu, 0xfa88584bu, 0x4e5c7da178b9e563uL, 0xd8a71f348e783f37uL, 0x300d70, 0x81df0a6c96766bf5uL, 0x27c0cb3900000000uL, 0xc97918a2a3eb7f6buL, t)
        }
        return packU64LE(words)
    }

    // 6473d0 Tenth (Out3 from ninth output; A from in2, B from sp430) + Final Out4
    fun builder6473d0TenthOut3Words(ninthOutput: ByteArray): UIntArray {
        val t = Tables.shared
        return UIntArray(V63) { u32Affine(rdU32(ninthOutput, it * 4), it, 0x11bbc8, 0x115448, t) }
    }

    fun builder6473d0TenthStreams(in2: ByteArray, sp430Words: UIntArray): Strm {
        val t = Tables.shared
        val a = ULongArray(V63) {
            streamWord6473(rdU32(in2, it * 4), it, 0x11a968, 0x11c388, 0x4c82531fu, 0x9d6c5712u, 0x7d67d167u, 0x9fe36605u, 0xdaea1b52b6016691uL, 0x3b80308f53669e2euL, 0x300df0, 0x4b523055abe88457uL, 0x418bbf9900000000uL, 0x70cd301dacb08338uL, t)
        }
        val b = ULongArray(V63) {
            streamWord6473(sp430Words[it], it, 0x11dca8, 0x114928, 0xfcd41a2bu, 0xf953e1f8u, 0xee73b901u, 0xce0a7777u, 0xa7fa1537bf414305uL, 0x07a282e2032d7105uL, 0x300e70, 0x4ebe8304c777f3a7uL, 0xa8b5cc4500000000uL, 0xe1237f2df16faf59uL, t)
        }
        return Strm(a, b, prefixSumsU64(a), prefixSumsU64(b))
    }

    fun builder6473d0Tenth64c524Workspace(in2: ByteArray, sp430Words: UIntArray): ByteArray {
        val s = builder6473d0TenthStreams(in2, sp430Words)
        return convolutionWorkspaceU64(s.a, s.b, 0x4f04381a3a25c55fuL, 0xeda7ceed201a4f20uL, 0x5f94dd0a9ca697b5uL, 0xacff7aae673c319cuL, 0x11489ebd7e12a758uL, 0xf234ac9ea555f56fuL, 0x4f16240dd4606c20uL)
    }

    fun builder6473d0FinalOut4Words(tenthOutput: ByteArray): UIntArray {
        val t = Tables.shared
        return UIntArray(V63) { u32Affine(rdU32(tenthOutput, it * 4), it, 0x11b408, 0x11dcc8, t) }
    }

    class Builder6473d0Result(
        val in0After: ByteArray, val in1After: ByteArray, val in2After: ByteArray,
        val out0: ByteArray, val out1: ByteArray, val out2: ByteArray, val out3: ByteArray, val out4: ByteArray,
    )

    fun builder6473d0OutputsFromBundledContext(in0: ByteArray, in1: ByteArray, in2: ByteArray, out0Preimage: ByteArray? = null, out1Preimage: ByteArray? = null): Builder6473d0Result =
        builder6473d0Outputs(in0, in1, in2, builder6388f0SharedContextFromBundle(), out0Preimage, out1Preimage)

    /** Full 6473d0 assembler: produces the out2/out3/out4 caller-row preimages from in0/in1/in2 + context. */
    fun builder6473d0Outputs(in0: ByteArray, in1: ByteArray, in2: ByteArray, contextSource: ByteArray, out0Preimage: ByteArray? = null, out1Preimage: ByteArray? = null): Builder6473d0Result {
        val vb = V63 * 4 // 88
        fun resolved(value: ByteArray?): ByteArray = if (value == null) ByteArray(vb) else value.copyOf(vb)
        val arg0 = contextSource.copyOfRange(0x100, 0x158)
        val scalar = rdU64(contextSource, 0x418)
        val out0Seed = resolved(out0Preimage)
        val out1Seed = resolved(out1Preimage)

        val firstOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0First64c524Workspace(in2)))
        val sp488 = builder6473d0SP488WordsFrom64c524Output(firstOutput)

        val secondOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Second64c524Workspace(out0Seed, sp488)))
        val thirdSP430 = builder6473d0ThirdSP430Words(builder6473d0ThirdSourceWords(secondOutput, contextSource, in0))

        val thirdOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Third64c524Workspace(in2, sp488)))
        val fourthOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Fourth64c524Workspace(out1Seed, thirdOutput)))
        val fifthSP3D8 = builder6473d0FifthSP3D8Words(builder6473d0FifthSourceWords(fourthOutput, contextSource, in1))

        val fifthOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Fifth64c524Workspace(thirdSP430)))
        val sp380 = builder6473d0SixthSP380Words(fifthOutput)
        val sixthOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Sixth64c524Workspace(thirdSP430, sp380)))

        val sp328 = builder6473d0SeventhSP328Words(sixthOutput)
        val seventhOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Seventh64c524Workspace(in0, sp380)))
        val sp2d0 = builder6473d0EighthSP2D0Words(seventhOutput)
        val eighthOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Eighth64c524Workspace(fifthSP3D8)))

        val out2 = builder6473d0NinthOut2Words(builder6473d0NinthFirstSourceWords(eighthOutput, contextSource, sp328, sp2d0))
        val sp278 = builder6473d0NinthSP278Words(builder6473d0NinthSecondSourceWords(sp2d0, contextSource, out2))
        val firstStreams = builder6473d0NinthFirstStreams(fifthSP3D8, sp278)
        val sp1c8 = builder6473d0NinthSP1C8Words(firstStreams.a, firstStreams.b)
        val secondStreams = builder6473d0NinthSecondStreams(in1, sp328)
        val sp118 = builder6473d0NinthSP118Words(secondStreams.a, secondStreams.b)
        val sp68 = builder6473d0NinthSP68Words(builder6473d0NinthThirdSourceWords(sp1c8, contextSource, sp118))
        val ninthOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Ninth64c524Workspace(sp68)))

        val out3 = builder6473d0TenthOut3Words(ninthOutput)
        val tenthOutput = packU32LE(builder64c524OutputWords(arg0, scalar, builder6473d0Tenth64c524Workspace(in2, thirdSP430)))
        val out4 = builder6473d0FinalOut4Words(tenthOutput)

        return Builder6473d0Result(
            in0.copyOf(vb), in1.copyOf(vb), in2.copyOf(vb),
            out0Seed, out1Seed, packU32LE(out2), packU32LE(out3), packU32LE(out4),
        )
    }

    // ============================================================
    // 6388f0 caller-row machinery (composes 642f60 + 6473d0 + 64cd40)
    // ============================================================

    class Builder6473d0OutputPreimages(
        val out4: ByteArray, val out3: ByteArray, val out2: ByteArray, val out1: ByteArray, val out0: ByteArray,
    )
    class Builder6388f0Caller64CallState(
        val arg0: ByteArray, val scalar: ULong, val x2Workspace: ByteArray, val x3Preimage: ByteArray, val stackWindow: ByteArray,
    )
    class Builder6388f0Caller64Call(
        val arg0: ByteArray, val scalar: ULong, val x2Workspace: ByteArray, val x3Preimage: ByteArray,
        val stackWindow: ByteArray, val output: ByteArray,
    )
    private class Builder6388f0SeededCaller64Tail(
        val next642f60: Builder6388f0Next642f60Inputs,
        val out4: ByteArray,
        val out3: ByteArray,
        val out2: ByteArray,
    )

    private const val CALLER_STACK_BYTES = 0x5000
    private const val CALLER_LOOP_ROWS = 59
    private const val CALLER_LOOP_ROW_BYTES = 0x58

    fun builder6388f0CallerContextFromBundle(): ByteArray {
        val t = Tables.shared
        val interleaved = t.callerLoopInterleaved6388f0
        val context = ByteArray(0x2d58)
        val shared = t.sharedContext6388f0
        System.arraycopy(shared, 0, context, 0, shared.size)
        for (row in 0 until CALLER_LOOP_ROWS) {
            val ro = row * (CALLER_LOOP_ROW_BYTES * 2)
            System.arraycopy(interleaved, ro, context, 0x4c8 + row * CALLER_LOOP_ROW_BYTES, CALLER_LOOP_ROW_BYTES)
            System.arraycopy(interleaved, ro + CALLER_LOOP_ROW_BYTES, context, 0x1910 + row * CALLER_LOOP_ROW_BYTES, CALLER_LOOP_ROW_BYTES)
        }
        return context
    }

    private val bundled6388f0CallerContext: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        builder6388f0CallerContextFromBundle()
    }

    fun builder6473d0MinimalStack20FromPreimages(p: Builder6473d0OutputPreimages): ByteArray {
        val vb = V63 * 4
        val stack20 = ByteArray(0xc00)
        System.arraycopy(p.out4, 0, stack20, 0x000, vb)
        System.arraycopy(p.out3, 0, stack20, 0x058, vb)
        System.arraycopy(p.out2, 0, stack20, 0x0b0, vb)
        System.arraycopy(p.out1, 0, stack20, 0x210, vb)
        System.arraycopy(p.out0, 0, stack20, 0x268, vb)
        return stack20
    }

    fun builder6473d0PostVectors(r: Builder6473d0Result): Map<Int, ByteArray> = mapOf(
        0x3708 to r.out4, 0x3760 to r.out3, 0x37b8 to r.out2, 0x3810 to r.in2After,
        0x3868 to r.in1After, 0x38c0 to r.in0After, 0x3918 to r.out1, 0x3970 to r.out0,
    )

    private fun place(dst: ByteArray, off: Int, src: ByteArray) = System.arraycopy(src, 0, dst, off, src.size)
    private fun wrU32(b: ByteArray, off: Int, v: UInt) { for (i in 0 until 4) b[off + i] = ((v shr (i * 8)) and 0xffu).toByte() }
    private fun wrU64(b: ByteArray, off: Int, v: ULong) { for (i in 0 until 8) b[off + i] = ((v shr (i * 8)) and 0xffuL).toByte() }

    private fun callerStreamU64(word: UInt, wordMul: ULong, wordAdd: ULong, foldTable: Int, foldMul: ULong, mixMul: ULong, mixAdd: ULong, t: Tables): ULong {
        val folded = fold63c278(word.toULong() * wordMul + wordAdd, foldTable, 8, t)
        return folded * foldMul + word.toULong() * mixMul + mixAdd
    }

    private fun callerStreamU64FirstNibble(word: UInt, wordMul: ULong, wordAdd: ULong, foldTable: Int, foldMul: ULong, mixMul: ULong, mixAdd: ULong, t: Tables): ULong {
        val product = word.toULong() * wordMul
        val folded = fold63c278FirstNibbleBeforeAdd(product, wordAdd, foldTable, t)
        return folded * foldMul + word.toULong() * mixMul + mixAdd
    }

    private fun convolution44(
        stack: ByteArray, aVecOff: Int, aPrefOff: Int, bVecOff: Int, bPrefOff: Int, outOff: Int,
        countMul: ULong, countAdd: ULong, productMul: ULong, bPrefixMul: ULong, aPrefixMul: ULong, finalMul: ULong, finalAdd: ULong,
    ) {
        val aVec = ULongArray(V63) { rdU64(stack, aVecOff + it * 8) }
        val aPrefix = ULongArray(V63) { rdU64(stack, aPrefOff + it * 8) }
        val bVec = ULongArray(V63) { rdU64(stack, bVecOff + it * 8) }
        val bPrefix = ULongArray(V63) { rdU64(stack, bPrefOff + it * 8) }
        for (index in 0 until 44) {
            val low = maxOf(0, index - (V63 - 1))
            val high = minOf(index, V63 - 1)
            if (low > high) { wrU64(stack, outOff + index * 8, countAdd * finalMul + finalAdd); continue }
            var productSum = 0uL
            for (bIndex in low..high) productSum += aVec[index - bIndex] * bVec[bIndex]
            var aSum = aPrefix[index - low]
            val prevA = index - high - 1
            if (prevA >= 0) aSum -= aPrefix[prevA]
            var bSum = bPrefix[high]
            if (low > 0) bSum -= bPrefix[low - 1]
            val count = (high - low + 1).toULong()
            var out = count * countMul + countAdd + productSum * productMul + bSum * bPrefixMul + aSum * aPrefixMul
            out = out * finalMul + finalAdd
            wrU64(stack, outOff + index * 8, out)
        }
    }

    fun builder6388f0Call64Call(state: Builder6388f0Caller64CallState): Builder6388f0Caller64Call {
        val output = packU32LE(builder64cd40OutputWords(state.arg0, state.scalar, state.x2Workspace))
        return Builder6388f0Caller64Call(state.arg0, state.scalar, state.x2Workspace, state.x3Preimage, state.stackWindow, output)
    }

    private fun builder6388f0Call64Output(state: Builder6388f0Caller64CallState): ByteArray =
        packU32LE(builder64cd40OutputWords(state.arg0, state.scalar, state.x2Workspace))

    fun builder6388f0First64cd40CallState(contextSource: ByteArray, callerStack20: ByteArray, postVectors: Map<Int, ByteArray>, entryIndex: Int): Builder6388f0Caller64CallState {
        val t = Tables.shared
        val stack = ByteArray(CALLER_STACK_BYTES)
        place(stack, 0x230, contextSource)
        place(stack, 0x3708, callerStack20)
        for ((off, raw) in postVectors) place(stack, off, raw)

        val loopSlot = entryIndex % CALLER_LOOP_ROWS
        val loopCounter = CALLER_LOOP_ROWS - 1 - loopSlot
        val pointerDelta = loopSlot * CALLER_LOOP_ROW_BYTES

        val aMul = 0x5025a2599f75877fuL; val aAdd = 0x4d8a8810a4bbc5a3uL
        val bMul = 0x23c3d48d0602f787uL; val bAdd = 0x62917fc875cc9e6buL
        val firstMixMul = 0x6f8d70f401079e5buL; val firstMixAdd = 0x31b3e556163432eduL
        val callerMixMul = 0x30eef2ed3a43a4f9uL; val callerMixAdd = 0x92ef60a176c7d6c9uL
        val firstFoldMul = 0x838e88db00000000uL; val callerFoldMul = 0x37fd608100000000uL

        val firstSrcWord = rdU32(stack, 0x38c0) * 0xc938d835u + 0xe6fc451bu
        val callerWord = rdU32(stack, 0x6f8 + loopCounter * 0x58) * 0xc955b06bu + 0x454427dfu
        val firstB = callerStreamU64(firstSrcWord, aMul, aAdd, 0x300ef0, firstFoldMul, firstMixMul, firstMixAdd, t)
        val firstA = callerStreamU64(callerWord, bMul, bAdd, 0x300f70, callerFoldMul, callerMixMul, callerMixAdd, t)
        wrU64(stack, 0x3b80, firstB); wrU64(stack, 0x4130, firstB)
        wrU64(stack, 0x39c8, firstA); wrU64(stack, 0x4010, firstA)

        var prefixB = firstB
        for (index in 1 until V63) {
            val to = (index * 4) and 0x1c
            var word = rdU32(stack, 0x38c0 + index * 4)
            word = word * u32Tbl(0x112e28 + to, t) + u32Tbl(0x117268 + to, t)
            word = word * 0x56d9f19bu + 0x64a9155bu
            val value = callerStreamU64(word, aMul, aAdd, 0x300ef0, firstFoldMul, firstMixMul, firstMixAdd, t)
            wrU64(stack, 0x3b80 + index * 8, value)
            prefixB += value
            wrU64(stack, 0x4130 + index * 8, prefixB)
        }

        var prefixA = firstA
        var callerStream = 0x1aec - pointerDelta
        for (index in 0 until V63 - 1) {
            val to = ((index + 1) and 7) * 4
            var word = rdU32(stack, callerStream + index * 4)
            word = word * u32Tbl(0x117288 + to, t) + u32Tbl(0x1205e8 + to, t)
            word = word * 0x994a2aa3u + 0x7f433349u
            val value = callerStreamU64(word, bMul, bAdd, 0x300f70, callerFoldMul, callerMixMul, callerMixAdd, t)
            wrU64(stack, 0x39d0 + index * 8, value)
            prefixA += value
            wrU64(stack, 0x4018 + index * 8, prefixA)
        }

        convolution44(stack, 0x39c8, 0x4010, 0x3b80, 0x4130, 0x3ce0,
            0x4079ef92755bf93auL, 0xb43c87132a6e84d1uL, 0x129a56bce90af833uL, 0x8de93a973ee9c82buL, 0xb3c2bc6591a8beaauL, 0xe6bf6d3dc98f10f7uL, 0x632718706bc72397uL)

        val cMul = 0x877a8a4a5f3b0f49uL; val cAdd = 0xa24f4a31979cc775uL
        val dMul = 0xddfbefdc018359d5uL; val dAdd = 0x7f589737aa46bdd5uL
        val cMixMul = 0x5a83f7862436b279uL; val cMixAdd = 0x77cf4bf823a845d0uL
        val dMixMul = 0x80c881a27926eee7uL; val dMixAdd = 0xeabaf4ef841c8c86uL
        val cFoldMul = 0xde34e64f00000000uL; val dFoldMul = 0x438d983500000000uL

        val firstSrcWord2 = rdU32(stack, 0x37b8) * 0xff9582fdu + 0xfc52cb23u
        val callerWord2 = rdU32(stack, 0x1b40 + loopCounter * 0x58) * 0xad09fb4bu + 0x4d566e95u
        val firstC = callerStreamU64(firstSrcWord2, cMul, cAdd, 0x300ff0, cFoldMul, cMixMul, cMixAdd, t)
        val firstD = callerStreamU64(callerWord2, dMul, dAdd, 0x301070, dFoldMul, dMixMul, dMixAdd, t)
        wrU64(stack, 0x39c8, firstC); wrU64(stack, 0x4010, firstC)
        wrU64(stack, 0x4130, firstD); wrU64(stack, 0x3ef0, firstD)

        var prefixC = firstC
        for (index in 1 until V63) {
            val to = (index * 4) and 0x1c
            var word = rdU32(stack, 0x37b8 + index * 4)
            word = word * u32Tbl(0x1218c8 + to, t) + u32Tbl(0x1221e8 + to, t)
            word = word * 0x2c6e5d55u + 0x63f5202du
            val value = callerStreamU64(word, cMul, cAdd, 0x300ff0, cFoldMul, cMixMul, cMixAdd, t)
            wrU64(stack, 0x39c8 + index * 8, value)
            prefixC += value
            wrU64(stack, 0x4010 + index * 8, prefixC)
        }

        var prefixD = firstD
        callerStream = 0x2f34 - pointerDelta
        for (index in 0 until V63 - 1) {
            val to = ((index + 1) and 7) * 4
            var word = rdU32(stack, callerStream + index * 4)
            word = word * u32Tbl(0x119708 + to, t) + u32Tbl(0x120608 + to, t)
            word = word * 0x206cd1f3u + 0x867e396du
            val value = callerStreamU64(word, dMul, dAdd, 0x301070, dFoldMul, dMixMul, dMixAdd, t)
            wrU64(stack, 0x4138 + index * 8, value)
            prefixD += value
            wrU64(stack, 0x3ef8 + index * 8, prefixD)
        }

        convolution44(stack, 0x4130, 0x3ef0, 0x39c8, 0x4010, 0x3b80,
            0xd16513f43f99d2c0uL, 0x5bdc507e86f7d211uL, 0x49fd76daa54ce93buL, 0x4a15e654a01bea9euL, 0xe49b61c39c833ce0uL, 0x9054b9a41de45a5buL, 0x9b016b93e5b24765uL)

        var offset = 0
        while (offset < 0x160) {
            val firstAv = rdU64(stack, 0x3ce0 + offset); val secondA = rdU64(stack, 0x3ce0 + offset + 8)
            val firstBv = rdU64(stack, 0x3b80 + offset); val secondB = rdU64(stack, 0x3b80 + offset + 8)
            wrU64(stack, 0x39c8 + offset, firstAv * 0xb8bc9deccc0ade89uL + 0xc46ffd16f1b1756fuL + firstBv * 0x9c308b62a744c677uL)
            wrU64(stack, 0x39c8 + offset + 8, secondA * 0xb8bc9deccc0ade89uL + 0xc46ffd16f1b1756fuL + secondB * 0x9c308b62a744c677uL)
            offset += 0x10
        }

        return Builder6388f0Caller64CallState(
            stack.copyOfRange(0x330, 0x388), rdU64(contextSource, 0x418),
            stack.copyOfRange(0x39c8, 0x3b28), stack.copyOfRange(0x3b28, 0x3b80), stack.copyOfRange(0x3778, 0x42c8),
        )
    }

    fun builder6388f0Second64cd40CallState(contextSource: ByteArray, callerStack20: ByteArray, postVectors: Map<Int, ByteArray>, first64cd40Output: ByteArray, entryIndex: Int): Builder6388f0Caller64CallState {
        val t = Tables.shared
        val stack = ByteArray(CALLER_STACK_BYTES)
        place(stack, 0x230, contextSource)
        place(stack, 0x3708, callerStack20)
        for ((off, raw) in postVectors) place(stack, off, raw)
        System.arraycopy(first64cd40Output, 0, stack, 0x3b28, V63 * 4)

        val loopSlot = entryIndex % CALLER_LOOP_ROWS
        val loopCounter = CALLER_LOOP_ROWS - 1 - loopSlot
        val pointerDelta = loopSlot * CALLER_LOOP_ROW_BYTES
        wrU32(stack, 0x44, rdU32(stack, 0x6f8 + loopCounter * 0x58))
        wrU32(stack, 0x40, rdU32(stack, 0x1b40 + loopCounter * 0x58))

        for (index in 0 until V63) {
            val to = (index * 4) and 0x1c
            val word = rdU32(stack, 0x3b28 + index * 4) * foldTbl(0x300ff0 + to, t) + u32Tbl(0x1184c8 + to, t)
            wrU32(stack, 0x154 + index * 4, word)
        }

        var callerWord = rdU32(stack, 0x44) * 0xa2e10181u + 0xd0b84b4au
        var postWord = rdU32(stack, 0x3868) * 0xea9bc62bu + 0x295fb23du
        var callerValue = callerStreamU64(callerWord, 0x67eb8e340bf68edduL, 0x7194bb146d6a6c98uL, 0x3013f0, 0xf883fc9300000000uL, 0x412cb68339b36b19uL, 0xe7c0e7165633369buL, t)
        var postValue = callerStreamU64(postWord, 0x1e34bf9de310fbcbuL, 0xe80eb386bd2c7669uL, 0x301370, 0xa4e2a4f300000000uL, 0x3f2d22f0405cf24fuL, 0x316c36e4735ae9bcuL, t)
        wrU64(stack, 0x4130, callerValue); wrU64(stack, 0x3ef0, callerValue)
        wrU64(stack, 0x3b80, postValue); wrU64(stack, 0x4010, postValue)

        var prefixPost = postValue
        for (index in 1 until V63) {
            val to = (index * 4) and 0x1c
            var word = rdU32(stack, 0x3868 + index * 4)
            word = word * u32Tbl(0x11c3c8 + to, t) + u32Tbl(0x11d4c8 + to, t)
            word = word * 0xc99643bbu + 0xac352509u
            val value = callerStreamU64(word, 0x1e34bf9de310fbcbuL, 0xe80eb386bd2c7669uL, 0x301370, 0xa4e2a4f300000000uL, 0x3f2d22f0405cf24fuL, 0x316c36e4735ae9bcuL, t)
            wrU64(stack, 0x3b80 + index * 8, value)
            prefixPost += value
            wrU64(stack, 0x4010 + index * 8, prefixPost)
        }

        var prefixCaller = callerValue
        var callerStream = 0x1aec - pointerDelta
        for (index in 0 until V63 - 1) {
            val to = ((index + 1) and 7) * 4
            var word = rdU32(stack, callerStream + index * 4)
            word = word * u32Tbl(0x1125e8 + to, t) + u32Tbl(0x118f08 + to, t)
            word = word * 0x31bbe0b7u + 0x3fe25e18u
            val value = callerStreamU64(word, 0x67eb8e340bf68edduL, 0x7194bb146d6a6c98uL, 0x3013f0, 0xf883fc9300000000uL, 0x412cb68339b36b19uL, 0xe7c0e7165633369buL, t)
            wrU64(stack, 0x4138 + index * 8, value)
            prefixCaller += value
            wrU64(stack, 0x3ef8 + index * 8, prefixCaller)
        }

        convolution44(stack, 0x4130, 0x3ef0, 0x3b80, 0x4010, 0x3ce0,
            0x31387af6df27bc34uL, 0x5e7eda1d7e652662uL, 0xdc67c7dbf68b7273uL, 0x8f98298f0679fa22uL, 0x662a1479caab56ceuL, 0x26efbb4b51cdc6b5uL, 0xc5b3a8b6b472e5d3uL)

        callerWord = rdU32(stack, 0x40) * 0x63dc1441u + 0xda7427c7u
        postWord = rdU32(stack, 0x3760) * 0xe609bd27u + 0x93c1ccd4u
        callerValue = callerStreamU64(callerWord, 0xdca944fb28ac47f7uL, 0xd57d3e716bf087fcuL, 0x3014f0, 0xd584887500000000uL, 0xb241122944abe41duL, 0xeb98df0f724a8bc5uL, t)
        postValue = callerStreamU64(postWord, 0x86835750d0f2d33duL, 0x93d6710e2805c2cduL, 0x301470, 0x749f87a500000000uL, 0x37836d2c6f35aeafuL, 0xabfb5017ca2ca427uL, t)
        wrU64(stack, 0x4010, callerValue); wrU64(stack, 0x3e40, callerValue)
        wrU64(stack, 0x4130, postValue); wrU64(stack, 0x3ef0, postValue)

        prefixPost = postValue
        for (index in 1 until V63) {
            val to = (index * 4) and 0x1c
            var word = rdU32(stack, 0x3760 + index * 4)
            word = word * u32Tbl(0x11e588 + to, t) + u32Tbl(0x122a68 + to, t)
            word = word * 0x712dee2fu + 0xecb470d1u
            val value = callerStreamU64(word, 0x86835750d0f2d33duL, 0x93d6710e2805c2cduL, 0x301470, 0x749f87a500000000uL, 0x37836d2c6f35aeafuL, 0xabfb5017ca2ca427uL, t)
            wrU64(stack, 0x4130 + index * 8, value)
            prefixPost += value
            wrU64(stack, 0x3ef0 + index * 8, prefixPost)
        }

        prefixCaller = callerValue
        callerStream = 0x2f34 - pointerDelta
        for (index in 0 until V63 - 1) {
            val to = ((index + 1) and 7) * 4
            var word = rdU32(stack, callerStream + index * 4)
            word = word * u32Tbl(0x120628 + to, t) + u32Tbl(0x122a88 + to, t)
            word = word * 0x26f75f39u + 0x1c4c83fcu
            val value = callerStreamU64(word, 0xdca944fb28ac47f7uL, 0xd57d3e716bf087fcuL, 0x3014f0, 0xd584887500000000uL, 0xb241122944abe41duL, 0xeb98df0f724a8bc5uL, t)
            wrU64(stack, 0x4018 + index * 8, value)
            prefixCaller += value
            wrU64(stack, 0x3e48 + index * 8, prefixCaller)
        }

        convolution44(stack, 0x4010, 0x3e40, 0x4130, 0x3ef0, 0x3b80,
            0xf8f0e2182e743120uL, 0x2ea75adafa845934uL, 0x49eba04bc8aba147uL, 0x2ae8b5655df9be65uL, 0xcc8eaf52163f5260uL, 0xfee73c0f7de3fa41uL, 0x7572d2a401ed3b6auL)

        var offset = 0
        while (offset < 0x160) {
            val firstAv = rdU64(stack, 0x3ce0 + offset); val secondA = rdU64(stack, 0x3ce0 + offset + 8)
            val firstBv = rdU64(stack, 0x3b80 + offset); val secondB = rdU64(stack, 0x3b80 + offset + 8)
            wrU64(stack, 0x39c8 + offset, firstAv * 0xf6ebf5f38b50e6e5uL + 0x08494646ffdad49auL + firstBv * 0x26f0954510cb129fuL)
            wrU64(stack, 0x39c8 + offset + 8, secondA * 0xf6ebf5f38b50e6e5uL + 0x08494646ffdad49auL + secondB * 0x26f0954510cb129fuL)
            offset += 0x10
        }

        return Builder6388f0Caller64CallState(
            stack.copyOfRange(0x330, 0x388), rdU64(contextSource, 0x418),
            stack.copyOfRange(0x39c8, 0x3b28), stack.copyOfRange(0x3b28, 0x3b80), stack.copyOfRange(0x3778, 0x42c8),
        )
    }

    fun builder6388f0Third64cd40CallState(contextSource: ByteArray, callerStack20: ByteArray, postVectors: Map<Int, ByteArray>, second64cd40Output: ByteArray, entryIndex: Int): Builder6388f0Caller64CallState {
        val t = Tables.shared
        val stack = ByteArray(CALLER_STACK_BYTES)
        place(stack, 0x230, contextSource)
        place(stack, 0x3708, callerStack20)
        for ((off, raw) in postVectors) place(stack, off, raw)
        System.arraycopy(second64cd40Output, 0, stack, 0x3b28, V63 * 4)

        val loopSlot = entryIndex % CALLER_LOOP_ROWS
        val loopCounter = CALLER_LOOP_ROWS - 1 - loopSlot
        val pointerDelta = loopSlot * CALLER_LOOP_ROW_BYTES
        wrU32(stack, 0x44, rdU32(stack, 0x6f8 + loopCounter * 0x58))
        wrU32(stack, 0x40, rdU32(stack, 0x1b40 + loopCounter * 0x58))

        for (index in 0 until V63) {
            val to = (index * 4) and 0x1c
            val word = rdU32(stack, 0x3b28 + index * 4) * u32Tbl(0x114948 + to, t) + u32Tbl(0x1184e8 + to, t)
            wrU32(stack, 0xfc + index * 4, word)
        }

        var callerWord = rdU32(stack, 0x44) * 0x52b341e9u + 0x8fe4704au
        var postWord = rdU32(stack, 0x3810) * 0xb1c3b83du + 0x4ac96e8du
        var callerValue = callerStreamU64(callerWord, 0xc601c25eb7863abbuL, 0x8a9ac40e5bfb780duL, 0x3015f0, 0x9897ad9900000000uL, 0xe76d920aeec9873duL, 0x63d22c2ddb82d5a1uL, t)
        var postValue = callerStreamU64(postWord, 0x16aacea9a72f0c45uL, 0xe952bbc97872445cuL, 0x301570, 0x7db1cb8100000000uL, 0x9c887c5c45db1a3buL, 0x6536302ead1b2169uL, t)
        wrU64(stack, 0x4130, callerValue); wrU64(stack, 0x3ef0, callerValue)
        wrU64(stack, 0x3b80, postValue); wrU64(stack, 0x4010, postValue)

        var prefixPost = postValue
        for (index in 1 until V63) {
            val to = (index * 4) and 0x1c
            var word = rdU32(stack, 0x3810 + index * 4)
            word = word * u32Tbl(0x120648 + to, t) + u32Tbl(0x122208 + to, t)
            word = word * 0xad44242bu + 0x28772583u
            val value = callerStreamU64(word, 0x16aacea9a72f0c45uL, 0xe952bbc97872445cuL, 0x301570, 0x7db1cb8100000000uL, 0x9c887c5c45db1a3buL, 0x6536302ead1b2169uL, t)
            wrU64(stack, 0x3b80 + index * 8, value)
            prefixPost += value
            wrU64(stack, 0x4010 + index * 8, prefixPost)
        }

        var prefixCaller = callerValue
        var callerStream = 0x1aec - pointerDelta
        for (index in 0 until V63 - 1) {
            val to = ((index + 1) and 7) * 4
            var word = rdU32(stack, callerStream + index * 4)
            word = word * u32Tbl(0x119728 + to, t) + u32Tbl(0x1218e8 + to, t)
            word = word * 0xb7911189u + 0x50798488u
            val value = callerStreamU64(word, 0xc601c25eb7863abbuL, 0x8a9ac40e5bfb780duL, 0x3015f0, 0x9897ad9900000000uL, 0xe76d920aeec9873duL, 0x63d22c2ddb82d5a1uL, t)
            wrU64(stack, 0x4138 + index * 8, value)
            prefixCaller += value
            wrU64(stack, 0x3ef8 + index * 8, prefixCaller)
        }

        convolution44(stack, 0x4130, 0x3ef0, 0x3b80, 0x4010, 0x3ce0,
            0x02949f32b4f07fd8uL, 0xbea7dd815afcbcc1uL, 0x7611b37d7c8f4475uL, 0xb4709b2cff94859cuL, 0xde2dc8d44e8f4662uL, 0x3b985d4b603f64d9uL, 0x9a4acc2ae823c739uL)

        callerWord = rdU32(stack, 0x40) * 0x10aa89f9u + 0x5f38d605u
        postWord = rdU32(stack, 0x3708) * 0x49dc9b53u + 0x4de59f05u
        callerValue = callerStreamU64FirstNibble(callerWord, 0x8b7f3e328f16058buL, 0xc7947e77ef912670uL, 0x3016f0, 0xbcbba6f500000000uL, 0x272b96c7a7cb8ff9uL, 0x7dff808a85cebcacuL, t)
        postValue = callerStreamU64(postWord, 0x4cea0abb01866b97uL, 0xd8436bdaf28ca051uL, 0x301670, 0x832f036300000000uL, 0x4c0e84f7d0089f9buL, 0xb5d5f7a06307c689uL, t)
        wrU64(stack, 0x4010, callerValue); wrU64(stack, 0x3e40, callerValue)
        wrU64(stack, 0x4130, postValue); wrU64(stack, 0x3ef0, postValue)

        prefixPost = postValue
        for (index in 1 until V63) {
            val to = (index * 4) and 0x1c
            var word = rdU32(stack, 0x3708 + index * 4)
            word = word * u32Tbl(0x115f28 + to, t) + u32Tbl(0x118508 + to, t)
            word = word * 0x68c9b103u + 0x45ce4a73u
            val value = callerStreamU64(word, 0x4cea0abb01866b97uL, 0xd8436bdaf28ca051uL, 0x301670, 0x832f036300000000uL, 0x4c0e84f7d0089f9buL, 0xb5d5f7a06307c689uL, t)
            wrU64(stack, 0x4130 + index * 8, value)
            prefixPost += value
            wrU64(stack, 0x3ef0 + index * 8, prefixPost)
        }

        prefixCaller = callerValue
        callerStream = 0x2f34 - pointerDelta
        for (index in 0 until V63 - 1) {
            val to = ((index + 1) and 7) * 4
            var word = rdU32(stack, callerStream + index * 4)
            word = word * u32Tbl(0x115468 + to, t) + u32Tbl(0x118528 + to, t)
            word = word * 0xf6c4e17du + 0x0882a9cfu
            val value = callerStreamU64FirstNibble(word, 0x8b7f3e328f16058buL, 0xc7947e77ef912670uL, 0x3016f0, 0xbcbba6f500000000uL, 0x272b96c7a7cb8ff9uL, 0x7dff808a85cebcacuL, t)
            wrU64(stack, 0x4018 + index * 8, value)
            prefixCaller += value
            wrU64(stack, 0x3e48 + index * 8, prefixCaller)
        }

        convolution44(stack, 0x4010, 0x3e40, 0x4130, 0x3ef0, 0x3b80,
            0x638b8c646690163auL, 0x96f60e030a9158deuL, 0x59ee1b3f304ea615uL, 0x3a17e3aa11527f5euL, 0xcf4a3bc55798adefuL, 0x15bcf2fe3c06e5afuL, 0xcc8339ef4cba9cd0uL)

        var offset = 0
        while (offset < 0x160) {
            val firstAv = rdU64(stack, 0x3ce0 + offset); val secondA = rdU64(stack, 0x3ce0 + offset + 8)
            val firstBv = rdU64(stack, 0x3b80 + offset); val secondB = rdU64(stack, 0x3b80 + offset + 8)
            wrU64(stack, 0x39c8 + offset, firstAv * 0x929296759110b0a3uL + 0x0ede13af97827959uL + firstBv * 0x70b7f6eaabceff57uL)
            wrU64(stack, 0x39c8 + offset + 8, secondA * 0x929296759110b0a3uL + 0x0ede13af97827959uL + secondB * 0x70b7f6eaabceff57uL)
            offset += 0x10
        }

        return Builder6388f0Caller64CallState(
            stack.copyOfRange(0x330, 0x388), rdU64(contextSource, 0x418),
            stack.copyOfRange(0x39c8, 0x3b28), stack.copyOfRange(0x3b28, 0x3b80), stack.copyOfRange(0x3778, 0x42c8),
        )
    }

    // ---- 6388f0 seeded caller row (642f60 → 6473d0 → 3× 64cd40 → next 642f60 inputs) ----

    class Builder6388f0Next642f60Inputs(val x0: ByteArray, val x1: ByteArray, val x2: ByteArray)

    class Builder6388f0SeededCaller64Row(
        val index: Int,
        val current642f60: Builder6388f0Next642f60Inputs,
        val preimages: Builder6473d0OutputPreimages,
        val after642f60: Builder642f60Result,
        val after6473d0: Builder6473d0Result,
        val minimalStack20: ByteArray,
        val first64cd40: Builder6388f0Caller64Call,
        val second64cd40: Builder6388f0Caller64Call,
        val third64cd40: Builder6388f0Caller64Call,
        val next642f60: Builder6388f0Next642f60Inputs,
    )

    private fun u32AffineBytes63c278(input: ByteArray, mulTable: Int, addTable: Int, t: Tables): ByteArray {
        val out = ByteArray(V63 * 4)
        for (index in 0 until V63) {
            val to = (index * 4) and 0x1c
            wrU32(out, index * 4, rdU32(input, index * 4) * u32Tbl(mulTable + to, t) + u32Tbl(addTable + to, t))
        }
        return out
    }

    private fun modularInverseOddU32(value: UInt): UInt {
        var inv = value
        repeat(5) { inv = inv * (2u - value * inv) }
        return inv
    }

    private fun u32AffineInverseBytes63c278(input: ByteArray, mulTable: Int, addTable: Int, t: Tables): ByteArray {
        val out = ByteArray(V63 * 4)
        for (index in 0 until V63) {
            val to = (index * 4) and 0x1c
            val mul = u32Tbl(mulTable + to, t)
            val add = u32Tbl(addTable + to, t)
            wrU32(out, index * 4, (rdU32(input, index * 4) - add) * modularInverseOddU32(mul))
        }
        return out
    }

    fun builder6388f0Next642f60InputsFrom64cd40Outputs(first: ByteArray, second: ByteArray, third: ByteArray): Builder6388f0Next642f60Inputs {
        val t = Tables.shared
        return Builder6388f0Next642f60Inputs(
            u32AffineBytes63c278(first, 0x11a988, 0x1184c8, t),
            u32AffineBytes63c278(second, 0x114948, 0x1184e8, t),
            u32AffineBytes63c278(third, 0x112608, 0x11a008, t),
        )
    }

    fun builder6388f0SeededCaller64Row(
        index: Int, current642f60: Builder6388f0Next642f60Inputs, preimages: Builder6473d0OutputPreimages, contextSource: ByteArray,
    ): Builder6388f0SeededCaller64Row {
        val after642f60 = builder642f60Outputs(current642f60.x0, current642f60.x1, current642f60.x2, contextSource)
        val after6473d0 = builder6473d0Outputs(after642f60.out0, after642f60.out1, after642f60.out2, contextSource, preimages.out0, preimages.out1)
        val minimalStack20 = builder6473d0MinimalStack20FromPreimages(preimages)
        val postVectors = builder6473d0PostVectors(after6473d0)

        val first64cd40 = builder6388f0Call64Call(builder6388f0First64cd40CallState(contextSource, minimalStack20, postVectors, index))
        val second64cd40 = builder6388f0Call64Call(builder6388f0Second64cd40CallState(contextSource, minimalStack20, postVectors, first64cd40.output, index))
        val third64cd40 = builder6388f0Call64Call(builder6388f0Third64cd40CallState(contextSource, minimalStack20, postVectors, second64cd40.output, index))
        val next642f60 = builder6388f0Next642f60InputsFrom64cd40Outputs(first64cd40.output, second64cd40.output, third64cd40.output)

        return Builder6388f0SeededCaller64Row(index, current642f60, preimages, after642f60, after6473d0, minimalStack20, first64cd40, second64cd40, third64cd40, next642f60)
    }

    private fun builder6388f0SeededCaller64Tail(
        index: Int, current642f60: Builder6388f0Next642f60Inputs, preimages: Builder6473d0OutputPreimages, contextSource: ByteArray,
    ): Builder6388f0SeededCaller64Tail {
        val after642f60 = builder642f60Outputs(current642f60.x0, current642f60.x1, current642f60.x2, contextSource)
        val after6473d0 = builder6473d0Outputs(after642f60.out0, after642f60.out1, after642f60.out2, contextSource, preimages.out0, preimages.out1)
        val minimalStack20 = builder6473d0MinimalStack20FromPreimages(preimages)
        val postVectors = builder6473d0PostVectors(after6473d0)

        val first64cd40Output = builder6388f0Call64Output(builder6388f0First64cd40CallState(contextSource, minimalStack20, postVectors, index))
        val second64cd40Output = builder6388f0Call64Output(builder6388f0Second64cd40CallState(contextSource, minimalStack20, postVectors, first64cd40Output, index))
        val third64cd40Output = builder6388f0Call64Output(builder6388f0Third64cd40CallState(contextSource, minimalStack20, postVectors, second64cd40Output, index))
        val next642f60 = builder6388f0Next642f60InputsFrom64cd40Outputs(first64cd40Output, second64cd40Output, third64cd40Output)

        return Builder6388f0SeededCaller64Tail(next642f60, after6473d0.out4, after6473d0.out3, after6473d0.out2)
    }

    // ---- 6388f0 stream-start seeds + full caller-row loop (118 rows; row0/row59 reseed) ----

    /** Candidate invariant 532-byte entry source consumed by the row-0 low-seed path. */
    val bundled6388f0LowSeedEntrySource: ByteArray = re.abbot.librecr.protocol.hexToBytes(
        "0500000701060202030607010603030501000503000607040400050105030105" +
        "0507020005000507050206000104000603060102060003070003020006010703" +
        "0303040500040303060500050705000306000704060701060200020002040701" +
        "0604030706020405010303030204040400040004070107020706040301070407" +
        "0207060403050302020201070700020001050603000500050607000505000707" +
        "0105070407010205010301020001040707060604050700010502000201020203" +
        "0702020502070700030707070002000401030303010204000702000106020703" +
        "0304000606040205040003050305030706020500030305030002040101030204" +
        "0305060407010400000204000307040401010706010607040205000503060000" +
        "0704040002050105000707030504030502060405050503010103030000040305" +
        "0007070207070301020204030701020706010305050506000401050700030607" +
        "0704050403060601050204020405030302060701040002030507000604020502" +
        "0705000306000000010303070402030204040303060507020500040603000607" +
        "0007000104000102050202060700020101050005050302050300030503000006" +
        "0404050005030107050505070203040604050402070007010106020401010005" +
        "0003070206060006000401020006010303020403020101010606050607030505" +
        "0403070707070507020300040707000304020001")

    private val streamStart642f60X2Source = byteArrayOf(
        0x4c, 0x2d, 0xf3.toByte(), 0x05, 0xdd.toByte(), 0xb7.toByte(), 0x0c, 0x76,
        0xe8.toByte(), 0x2a, 0xd4.toByte(), 0x04, 0x3b, 0xe2.toByte(), 0xee.toByte(), 0xa5.toByte(),
        0x81.toByte(), 0xab.toByte(), 0x69, 0xf3.toByte(), 0x7c, 0xaa.toByte(), 0x49, 0xf5.toByte(),
        0xfa.toByte(), 0x7d, 0x43, 0x81.toByte(), 0x2f, 0x10, 0x25, 0x05,
        0xd3.toByte(), 0x4e, 0x67, 0xbe.toByte(), 0x8d.toByte(), 0x2e, 0x98.toByte(), 0xd3.toByte(),
        0xe8.toByte(), 0x2a, 0xe3.toByte(), 0x78, 0x3b, 0x32, 0xb0.toByte(), 0x16,
        0x81.toByte(), 0xab.toByte(), 0x69, 0x5c, 0xe3.toByte(), 0x6c, 0x62, 0xec.toByte(),
        0xa5.toByte(), 0x62, 0x1f, 0xaf.toByte(), 0xcf.toByte(), 0x68, 0x46, 0x16,
        0xc5.toByte(), 0x19, 0x8a.toByte(), 0x79, 0x48, 0xa0.toByte(), 0x3a, 0xd3.toByte(),
        0xe8.toByte(), 0x2a, 0xe3.toByte(), 0x78, 0x3b, 0x32, 0xb0.toByte(), 0x16,
        0x81.toByte(), 0xab.toByte(), 0x69, 0x5c, 0xe3.toByte(), 0x6c, 0x62, 0xec.toByte(),
    )

    fun builder6388f0StreamStart642f60X0FromOut0Seed(out0Seed: ByteArray): ByteArray =
        u32AffineBytes63c278(out0Seed, 0x11fd68, 0x1233a8, Tables.shared)

    fun builder6388f0StreamStart642f60X1FromOut1Seed(out1Seed: ByteArray): ByteArray =
        u32AffineBytes63c278(out1Seed, 0x115328, 0x11b2c8, Tables.shared)

    fun builder6388f0RecoverStreamStartOut0SeedFrom642f60X0(x0Source: ByteArray): ByteArray =
        u32AffineInverseBytes63c278(x0Source, 0x11fd68, 0x1233a8, Tables.shared)

    fun builder6388f0RecoverStreamStartOut1SeedFrom642f60X1(x1Source: ByteArray): ByteArray =
        u32AffineInverseBytes63c278(x1Source, 0x115328, 0x11b2c8, Tables.shared)

    class Builder6388f0FirstPair642f60Starts(val row0: Builder6388f0Next642f60Inputs, val row59: Builder6388f0Next642f60Inputs)

    fun builder6388f0StreamStart642f60Inputs(out0Seed: ByteArray, out1Seed: ByteArray, x2Source: ByteArray? = null): Builder6388f0Next642f60Inputs {
        val resolvedX2 = x2Source ?: streamStart642f60X2Source
        return Builder6388f0Next642f60Inputs(
            builder6388f0StreamStart642f60X0FromOut0Seed(out0Seed),
            builder6388f0StreamStart642f60X1FromOut1Seed(out1Seed),
            resolvedX2.copyOf(V63 * 4),
        )
    }

    fun builder6388f0FirstPair642f60StreamStarts(
        row0Out0Seed: ByteArray, row0Out1Seed: ByteArray, row59Out0Seed: ByteArray, row59Out1Seed: ByteArray, x2Source: ByteArray? = null,
    ): Builder6388f0FirstPair642f60Starts = Builder6388f0FirstPair642f60Starts(
        builder6388f0StreamStart642f60Inputs(row0Out0Seed, row0Out1Seed, x2Source),
        builder6388f0StreamStart642f60Inputs(row59Out0Seed, row59Out1Seed, x2Source),
    )

    fun builder6388f0SeededCaller64Rows(
        starts: Builder6388f0FirstPair642f60Starts,
        row0LowPreimages: Builder6473d0OutputPreimages,
        contextSource: ByteArray? = null,
        limit: Int = 118,
        x2Source: ByteArray? = null,
    ): List<Builder6388f0SeededCaller64Row> {
        require(limit in 0..(CALLER_LOOP_ROWS * 2)) { "invalid 6388f0 row limit $limit" }
        val context = contextSource ?: bundled6388f0CallerContext

        val row0Out0 = builder6388f0RecoverStreamStartOut0SeedFrom642f60X0(starts.row0.x0)
        val row0Out1 = builder6388f0RecoverStreamStartOut1SeedFrom642f60X1(starts.row0.x1)
        val row59Out0 = builder6388f0RecoverStreamStartOut0SeedFrom642f60X0(starts.row59.x0)
        val row59Out1 = builder6388f0RecoverStreamStartOut1SeedFrom642f60X1(starts.row59.x1)
        val row0Start = builder6388f0StreamStart642f60Inputs(row0Out0, row0Out1, x2Source)
        val row59Start = builder6388f0StreamStart642f60Inputs(row59Out0, row59Out1, x2Source)

        val rows = ArrayList<Builder6388f0SeededCaller64Row>(limit)
        var previous6473d0: Builder6473d0Result? = null
        var carried642f60: Builder6388f0Next642f60Inputs? = null
        var activeOut0Seed: ByteArray? = null
        var activeOut1Seed: ByteArray? = null

        for (index in 0 until limit) {
            val current642f60: Builder6388f0Next642f60Inputs
            val preimages: Builder6473d0OutputPreimages
            when (index) {
                0 -> {
                    current642f60 = row0Start
                    activeOut0Seed = row0Out0; activeOut1Seed = row0Out1
                    preimages = Builder6473d0OutputPreimages(row0LowPreimages.out4, row0LowPreimages.out3, row0LowPreimages.out2, row0Out1, row0Out0)
                }
                CALLER_LOOP_ROWS -> {
                    val prev = previous6473d0 ?: error("invalid 6388f0 entry index $index")
                    current642f60 = row59Start
                    activeOut0Seed = row59Out0; activeOut1Seed = row59Out1
                    preimages = Builder6473d0OutputPreimages(prev.out4, prev.out3, prev.out2, row59Out1, row59Out0)
                }
                else -> {
                    val prev = previous6473d0 ?: error("invalid 6388f0 entry index $index")
                    current642f60 = carried642f60 ?: error("invalid 6388f0 entry index $index")
                    preimages = Builder6473d0OutputPreimages(prev.out4, prev.out3, prev.out2, activeOut1Seed!!, activeOut0Seed!!)
                }
            }
            val row = builder6388f0SeededCaller64Row(index, current642f60, preimages, context)
            rows.add(row)
            previous6473d0 = row.after6473d0
            carried642f60 = row.next642f60
        }
        return rows
    }

    // ============================================================
    // 6388f0 lane → pack → stage → prefinal → internal → raw → source pipeline
    // ============================================================

    private fun vm638840(magic: Long, src1: ByteArray, src2: ByteArray, t: Tables): ByteArray {
        val progOff = (magic and 0x3fffffL).toInt()
        val count = ((magic ushr 36) and 0x3fffL).toInt()
        val tail = (magic ushr 50).toInt()
        val total = count + tail
        val prog = checkedSlice(t.prog638840, progOff, total)
        val out = ByteArray(total)
        var state = 0
        for (i in 0 until count) {
            state = step(state, src1[i].toInt() and 0xff, src2[i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        for (i in 0 until tail) {
            val pos = count + i
            state = step(state, null, src2[pos].toInt() and 0xff, prog[pos].toInt() and 0xff, t)
            out[pos] = (state and 7).toByte()
        }
        return out
    }

    private fun vm6420d8(magic: Long, src1: ByteArray, src2: ByteArray, t: Tables): ByteArray {
        val progOff = (magic and 0x3fffffL).toInt()
        val primer = ((magic ushr 22) and 0x3fffL).toInt()
        val count = ((magic ushr 36) and 0x3fffL).toInt()
        val tail = (magic ushr 50).toInt()
        val prog = checkedSlice(t.prog638840, progOff, primer + count + tail)
        var state = 0
        for (i in 0 until primer) {
            state = step(state, src1[i].toInt() and 0xff, src2[i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
        }
        val out = ByteArray(count + tail)
        for (i in 0 until count) {
            val pos = primer + i
            state = step(state, src1[pos].toInt() and 0xff, src2[pos].toInt() and 0xff, prog[pos].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        for (i in 0 until tail) {
            val pos = primer + count + i
            state = step(state, null, null, prog[pos].toInt() and 0xff, t)
            out[count + i] = (state and 7).toByte()
        }
        return out
    }

    private fun laneTable6388f0Expand(tableOffset: Int, selectorByte: Int, t: Tables): ByteArray {
        val row = checkedSlice(t.laneTables6388f0, tableOffset + selectorByte * 9, 9)
        val out = ByteArray(18)
        for (i in 0 until 9) {
            out[i * 2] = (row[i].toInt() and 7).toByte()
            out[i * 2 + 1] = ((row[i].toInt() ushr 3) and 7).toByte()
        }
        return out
    }

    private fun lanePrefixed6388f0(prefixWord: UInt, state18: ByteArray): ByteArray {
        val out = ByteArray(18)
        for (k in 0 until 4) out[k] = ((prefixWord shr (k * 8)) and 0xffu).toByte()
        System.arraycopy(state18, 0, out, 4, 14)
        return out
    }

    private fun selector6388f0(index: Int, scheduleWord: UInt, t: Tables): UInt {
        val to = (index * 4) and 0x1c
        return scheduleWord * rdU32(t.selectorMul6388f0, to) + rdU32(t.selectorAdd6388f0, to)
    }

    class Builder6388f0LaneResult(val primaryLaneBlocks: ByteArray, val secondaryLaneBlocks: ByteArray)
    class Builder6388f0PackResult(val stageBPackHead16: ByteArray, val stageBPackBody16: ByteArray, val stageAPackHead16: ByteArray, val stageAPackBody16: ByteArray)
    class Builder6388f0StageResult(val stageASource: ByteArray, val stageBSource: ByteArray)
    class Builder6388f0Seeded63c278Stream(val rowIndex: Int, val arg0: ByteArray, val arg1: ByteArray, val arg2: ByteArray, val scalar: ULong, val scheduleWords: UIntArray)
    class Builder6388f0Seeded63c278Schedules(val first: Builder6388f0Seeded63c278Stream, val second: Builder6388f0Seeded63c278Stream)

    fun builder6388f0LaneBlocksFromScheduleWords(scheduleWords: UIntArray): Builder6388f0LaneResult {
        require(scheduleWords.size == 20) { "invalid 6388f0 schedule word count ${scheduleWords.size}" }
        val t = Tables.shared
        val primaryStatic = checkedSlice(t.laneTables6388f0, 0x1224, 18)
        val secondaryStatic = checkedSlice(t.laneTables6388f0, 0x1236, 18)
        val primaryLanes = ByteArray(320)
        val secondaryLanes = ByteArray(320)
        for (index in scheduleWords.indices) {
            val selector = selector6388f0(index, scheduleWords[index], t)
            var primaryState = checkedSlice(t.laneTables6388f0, 0x1200, 0x10) + byteArrayOf(0x05, 0x04)
            var secondaryState = checkedSlice(t.laneTables6388f0, 0x1212, 0x10) + byteArrayOf(0x05, 0x02)
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val primaryPrimer = lanePrefixed6388f0(0x03000000u, primaryState)
                primaryState = vm638840(0x1200000712fL, primaryPrimer, primaryPrimer, t)
                val secondaryPrimer = lanePrefixed6388f0(0x01000000u, secondaryState)
                secondaryState = vm638840(0x120000003aaL, secondaryPrimer, secondaryPrimer, t)
                val selectorByte = ((selector shr shift) and 0xffu).toInt()
                primaryState = vm638840(0x1200000551aL, primaryState, laneTable6388f0Expand(0x0000, selectorByte, t), t)
                secondaryState = vm638840(0x12000000c60L, secondaryState, laneTable6388f0Expand(0x0900, selectorByte, t), t)
            }
            val primarySource = vm638840(0x12000003d45L, primaryState, primaryStatic, t)
            val secondarySource = vm638840(0x12000005e9dL, secondaryState, secondaryStatic, t)
            System.arraycopy(vm638840(0x10000000214L, primarySource, primarySource, t), 0, primaryLanes, index * 16, 16)
            System.arraycopy(vm638840(0x10000003231L, secondarySource, secondarySource, t), 0, secondaryLanes, index * 16, 16)
        }
        return Builder6388f0LaneResult(primaryLanes, secondaryLanes)
    }

    fun builder6388f0PackOutputsFromLaneBlocks(primaryLaneBlocks: ByteArray, secondaryLaneBlocks: ByteArray): Builder6388f0PackResult {
        require(primaryLaneBlocks.size == 320 && secondaryLaneBlocks.size == 320)
        val t = Tables.shared
        val primary = Array(20) { primaryLaneBlocks.copyOfRange(it * 16, it * 16 + 16) }
        val secondary = Array(20) { secondaryLaneBlocks.copyOfRange(it * 16, it * 16 + 16) }
        val stageBPackHead = vm638840(0x10000000388L, primary[0], primary[0], t)
        val stageBPackBody = ByteArray(19 * 16)
        for (i in 1 until 20) System.arraycopy(vm638840(0x10000003bd7L, primary[i], primary[i], t), 0, stageBPackBody, (i - 1) * 16, 16)
        val stageAPackHead = vm638840(0x100000062d7L, secondary[0], secondary[0], t)
        val stageAPackBody = ByteArray(19 * 16)
        for (i in 1 until 20) System.arraycopy(vm638840(0x10000008177L, secondary[i], secondary[i], t), 0, stageAPackBody, (i - 1) * 16, 16)
        return Builder6388f0PackResult(stageBPackHead, stageBPackBody, stageAPackHead, stageAPackBody)
    }

    private fun pack6388f0Twenty16To282(head16: ByteArray, bodyBlocks16: ByteArray): ByteArray {
        val out = ByteArray(282)
        System.arraycopy(head16, 0, out, 0, 16)
        var w = 16; var off = 0
        while (off < bodyBlocks16.size) {
            System.arraycopy(bodyBlocks16, off + 2, out, w, 14)
            w += 14; off += 16
        }
        return out
    }

    fun builder6388f0Len32StageInputsFromPackOutputs(stageBPackHead16: ByteArray, stageBPackBody16: ByteArray, stageAPackHead16: ByteArray, stageAPackBody16: ByteArray): Builder6388f0StageResult {
        val t = Tables.shared
        val stageBPack = pack6388f0Twenty16To282(stageBPackHead16, stageBPackBody16)
        val stageBSource = vm638840(0x11a000000a2cL, stageBPack, stageBPack, t)
        val stageASource = pack6388f0Twenty16To282(stageAPackHead16, stageAPackBody16)
        return Builder6388f0StageResult(stageASource, stageBSource)
    }

    fun builder6388f0Len32PrefinalSourcesFromWorkspace(workspaceSource: ByteArray): ByteArray {
        require(workspaceSource.size == 0x10a) { "invalid 6388f0 workspace length ${workspaceSource.size}" }
        val t = Tables.shared
        val workspace = vm638840(0x10a000006cd1L, workspaceSource, workspaceSource, t)
        val firstPrefinal = workspace.copyOfRange(0, 0x42)
        val updated = vm6420d8(0x1000ca0100063f1L, workspace, workspace, t)
        val secondPrefinal = updated.copyOfRange(0, 0x42)
        return firstPrefinal + secondPrefinal
    }

    fun builder6388f0Len32PrefinalSourcesFromStageInputs(stageASource: ByteArray, stageBSource: ByteArray): ByteArray {
        require(stageASource.size == 0x11a && stageBSource.size == 0x11a)
        val t = Tables.shared
        val stageA = vm638840(0x11a000003e76L, stageASource, stageASource, t)
        val stageB = vm638840(0x11a000004f0cL, stageBSource, stageA, t)
        val stageC = vm638840(0x11a000004b16L, stageB, stageA, t)
        val stageDSource = stageC.copyOfRange(0, 0x10a)
        val stageD = vm638840(0x10a0000078bbL, stageDSource, stageDSource, t)
        return builder6388f0Len32PrefinalSourcesFromWorkspace(stageD)
    }

    fun builder6388f0PrefinalLen32InternalBlocks(prefinalSourceBlocks: ByteArray): ByteArray {
        require(prefinalSourceBlocks.size == 2 * 0x42) { "invalid 6388f0 prefinal length ${prefinalSourceBlocks.size}" }
        val t = Tables.shared
        val call0 = prefinalSourceBlocks.copyOfRange(0, 0x42)
        val call1 = prefinalSourceBlocks.copyOfRange(0x42, 2 * 0x42)
        val block1 = vm638840(0x42000003bf9L, call0, call0, t)
        val block0 = vm638840(0x42000003bf9L, call1, call1, t)
        return block0 + block1
    }

    fun builder6388f0FinalRawBlocks(internalBlocks: ByteArray): ByteArray {
        require(internalBlocks.size % 0x42 == 0) { "invalid encoded block length ${internalBlocks.size}" }
        val t = Tables.shared
        val out = ByteArray(internalBlocks.size)
        var start = 0
        while (start < internalBlocks.size) {
            val block = internalBlocks.copyOfRange(start, start + 0x42)
            System.arraycopy(vm638840(0x42000007e29L, block, block, t), 0, out, start, 0x42)
            start += 0x42
        }
        return out
    }

    fun deriveFrom660448Sources(firstRawBlocks: ByteArray, secondRawBlocks: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray =
        deriveFrom660448RawDescriptor(firstRawBlocks + secondRawBlocks, src4, offset, length)

    fun deriveFrom64d774RawStreams(firstRawBlocks: ByteArray, secondRawBlocks: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray =
        deriveFrom660448Sources(constructor670a54Ptr10Blocks(firstRawBlocks), constructor670a54Ptr10Blocks(secondRawBlocks), src4, offset, length)

    fun deriveFrom6388f0InternalStreams(firstInternalBlocks: ByteArray, secondInternalBlocks: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray =
        deriveFrom64d774RawStreams(builder6388f0FinalRawBlocks(firstInternalBlocks), builder6388f0FinalRawBlocks(secondInternalBlocks), src4, offset, length)

    fun deriveFrom6388f0PrefinalLen32Streams(firstPrefinalBlocks: ByteArray, secondPrefinalBlocks: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray =
        deriveFrom6388f0InternalStreams(builder6388f0PrefinalLen32InternalBlocks(firstPrefinalBlocks), builder6388f0PrefinalLen32InternalBlocks(secondPrefinalBlocks), src4, offset, length)

    fun deriveFrom6388f0WorkspaceLen32Streams(firstWorkspaceSource: ByteArray, secondWorkspaceSource: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray =
        deriveFrom6388f0PrefinalLen32Streams(builder6388f0Len32PrefinalSourcesFromWorkspace(firstWorkspaceSource), builder6388f0Len32PrefinalSourcesFromWorkspace(secondWorkspaceSource), src4, offset, length)

    fun deriveFrom6388f0StageLen32Streams(firstStageASource: ByteArray, firstStageBSource: ByteArray, secondStageASource: ByteArray, secondStageBSource: ByteArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray =
        deriveFrom6388f0PrefinalLen32Streams(
            builder6388f0Len32PrefinalSourcesFromStageInputs(firstStageASource, firstStageBSource),
            builder6388f0Len32PrefinalSourcesFromStageInputs(secondStageASource, secondStageBSource), src4, offset, length)

    fun deriveFrom6388f0PackLen32Streams(
        firstStageBPackHead16: ByteArray, firstStageBPackBody16: ByteArray, firstStageAPackHead16: ByteArray, firstStageAPackBody16: ByteArray,
        secondStageBPackHead16: ByteArray, secondStageBPackBody16: ByteArray, secondStageAPackHead16: ByteArray, secondStageAPackBody16: ByteArray,
        src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10,
    ): ByteArray {
        val firstStage = builder6388f0Len32StageInputsFromPackOutputs(firstStageBPackHead16, firstStageBPackBody16, firstStageAPackHead16, firstStageAPackBody16)
        val secondStage = builder6388f0Len32StageInputsFromPackOutputs(secondStageBPackHead16, secondStageBPackBody16, secondStageAPackHead16, secondStageAPackBody16)
        return deriveFrom6388f0StageLen32Streams(firstStage.stageASource, firstStage.stageBSource, secondStage.stageASource, secondStage.stageBSource, src4, offset, length)
    }

    fun deriveFrom6388f0LaneLen32Streams(
        firstPrimaryLaneBlocks: ByteArray, firstSecondaryLaneBlocks: ByteArray, secondPrimaryLaneBlocks: ByteArray, secondSecondaryLaneBlocks: ByteArray,
        src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10,
    ): ByteArray {
        val firstPack = builder6388f0PackOutputsFromLaneBlocks(firstPrimaryLaneBlocks, firstSecondaryLaneBlocks)
        val secondPack = builder6388f0PackOutputsFromLaneBlocks(secondPrimaryLaneBlocks, secondSecondaryLaneBlocks)
        return deriveFrom6388f0PackLen32Streams(
            firstPack.stageBPackHead16, firstPack.stageBPackBody16, firstPack.stageAPackHead16, firstPack.stageAPackBody16,
            secondPack.stageBPackHead16, secondPack.stageBPackBody16, secondPack.stageAPackHead16, secondPack.stageAPackBody16, src4, offset, length)
    }

    fun deriveFrom6388f0ScheduleLen32Streams(firstScheduleWords: UIntArray, secondScheduleWords: UIntArray, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray {
        val firstLanes = builder6388f0LaneBlocksFromScheduleWords(firstScheduleWords)
        val secondLanes = builder6388f0LaneBlocksFromScheduleWords(secondScheduleWords)
        return deriveFrom6388f0LaneLen32Streams(firstLanes.primaryLaneBlocks, firstLanes.secondaryLaneBlocks, secondLanes.primaryLaneBlocks, secondLanes.secondaryLaneBlocks, src4, offset, length)
    }

    fun builder6388f0Seeded63c278SchedulesFromRows(rows: List<Builder6388f0SeededCaller64Row>): Builder6388f0Seeded63c278Schedules =
        builder6388f0Seeded63c278SchedulesFromRows(rows, pre63c278Arg0Source, pre63c278Scalar)

    fun builder6388f0Seeded63c278SchedulesFromRows(rows: List<Builder6388f0SeededCaller64Row>, arg0: ByteArray, scalar: ULong): Builder6388f0Seeded63c278Schedules {
        require(rows.size >= CALLER_LOOP_ROWS * 2) { "invalid 6388f0 row limit ${rows.size}" }
        require(arg0.size >= V63 * 4)
        val arg0Prefix = arg0.copyOf(V63 * 4)
        fun stream(rowIndex: Int): Builder6388f0Seeded63c278Stream {
            val row = rows[rowIndex]
            val arg1 = row.next642f60.x0
            val arg2 = row.next642f60.x2
            return Builder6388f0Seeded63c278Stream(rowIndex, arg0Prefix, arg1, arg2, scalar, builder63c278ScheduleWords(arg0Prefix, arg1, arg2, scalar))
        }
        return Builder6388f0Seeded63c278Schedules(stream(CALLER_LOOP_ROWS - 1), stream(CALLER_LOOP_ROWS * 2 - 1))
    }

    fun builder6388f0Seeded63c278SchedulesFromFirstPairStreamSeeds(
        seeds: Builder6388f0FirstPairStreamSeeds,
        arg0: ByteArray = pre63c278Arg0Source,
        scalar: ULong = pre63c278Scalar,
        contextSource: ByteArray? = null,
        x2Source: ByteArray? = null,
    ): Builder6388f0Seeded63c278Schedules {
        require(arg0.size >= V63 * 4)
        val starts = builder6388f0FirstPair642f60StreamStarts(seeds, x2Source)
        val row0LowPreimages = Builder6473d0OutputPreimages(seeds.row0Out4, seeds.row0Out3, seeds.row0Out2, seeds.row0Out1, seeds.row0Out0)
        val context = contextSource ?: bundled6388f0CallerContext

        val row0Out0 = builder6388f0RecoverStreamStartOut0SeedFrom642f60X0(starts.row0.x0)
        val row0Out1 = builder6388f0RecoverStreamStartOut1SeedFrom642f60X1(starts.row0.x1)
        val row59Out0 = builder6388f0RecoverStreamStartOut0SeedFrom642f60X0(starts.row59.x0)
        val row59Out1 = builder6388f0RecoverStreamStartOut1SeedFrom642f60X1(starts.row59.x1)
        val row0Start = builder6388f0StreamStart642f60Inputs(row0Out0, row0Out1, x2Source)
        val row59Start = builder6388f0StreamStart642f60Inputs(row59Out0, row59Out1, x2Source)
        val arg0Prefix = arg0.copyOf(V63 * 4)

        var previousOut4: ByteArray? = null
        var previousOut3: ByteArray? = null
        var previousOut2: ByteArray? = null
        var carried642f60: Builder6388f0Next642f60Inputs? = null
        var activeOut0Seed: ByteArray? = null
        var activeOut1Seed: ByteArray? = null
        var first: Builder6388f0Seeded63c278Stream? = null
        var second: Builder6388f0Seeded63c278Stream? = null

        for (index in 0 until CALLER_LOOP_ROWS * 2) {
            val current642f60: Builder6388f0Next642f60Inputs
            val preimages: Builder6473d0OutputPreimages
            when (index) {
                0 -> {
                    current642f60 = row0Start
                    activeOut0Seed = row0Out0
                    activeOut1Seed = row0Out1
                    preimages = row0LowPreimages
                }
                CALLER_LOOP_ROWS -> {
                    current642f60 = row59Start
                    activeOut0Seed = row59Out0
                    activeOut1Seed = row59Out1
                    preimages = Builder6473d0OutputPreimages(
                        previousOut4 ?: error("invalid 6388f0 entry index $index"),
                        previousOut3 ?: error("invalid 6388f0 entry index $index"),
                        previousOut2 ?: error("invalid 6388f0 entry index $index"),
                        row59Out1,
                        row59Out0,
                    )
                }
                else -> {
                    current642f60 = carried642f60 ?: error("invalid 6388f0 entry index $index")
                    preimages = Builder6473d0OutputPreimages(
                        previousOut4 ?: error("invalid 6388f0 entry index $index"),
                        previousOut3 ?: error("invalid 6388f0 entry index $index"),
                        previousOut2 ?: error("invalid 6388f0 entry index $index"),
                        activeOut1Seed ?: error("invalid 6388f0 entry index $index"),
                        activeOut0Seed ?: error("invalid 6388f0 entry index $index"),
                    )
                }
            }

            val row = builder6388f0SeededCaller64Tail(index, current642f60, preimages, context)
            if (index == CALLER_LOOP_ROWS - 1 || index == CALLER_LOOP_ROWS * 2 - 1) {
                val stream = Builder6388f0Seeded63c278Stream(
                    index,
                    arg0Prefix,
                    row.next642f60.x0,
                    row.next642f60.x2,
                    scalar,
                    builder63c278ScheduleWords(arg0Prefix, row.next642f60.x0, row.next642f60.x2, scalar),
                )
                if (index == CALLER_LOOP_ROWS - 1) first = stream else second = stream
            }
            previousOut4 = row.out4
            previousOut3 = row.out3
            previousOut2 = row.out2
            carried642f60 = row.next642f60
        }

        return Builder6388f0Seeded63c278Schedules(
            first ?: error("missing first 6388f0 schedule"),
            second ?: error("missing second 6388f0 schedule"),
        )
    }

    fun deriveFrom6388f0SeededCaller64Rows(rows: List<Builder6388f0SeededCaller64Row>, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray =
        deriveFrom6388f0SeededCaller64Rows(rows, pre63c278Arg0Source, pre63c278Scalar, src4, offset, length)

    fun deriveFrom6388f0SeededCaller64Rows(rows: List<Builder6388f0SeededCaller64Row>, arg0: ByteArray, scalar: ULong, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray {
        val schedules = builder6388f0Seeded63c278SchedulesFromRows(rows, arg0, scalar)
        return deriveFrom6388f0ScheduleLen32Streams(schedules.first.scheduleWords, schedules.second.scheduleWords, src4, offset, length)
    }

    fun phase5RawKeyFrom6388f0SeededCaller64Rows(rows: List<Builder6388f0SeededCaller64Row>, offset: Int = 0): ByteArray =
        Phase5KeySchedule.deriveRawKey(deriveFrom6388f0SeededCaller64Rows(rows, offset = offset, length = 0x10))

    fun phase5RawKeyFrom6388f0ScheduleLen32Streams(firstScheduleWords: UIntArray, secondScheduleWords: UIntArray, offset: Int = 0): ByteArray =
        Phase5KeySchedule.deriveRawKey(deriveFrom6388f0ScheduleLen32Streams(firstScheduleWords, secondScheduleWords, offset = offset, length = 0x10))

    // ---- 6388f0 first-pair stream seeds (out-seeds → 118 rows → source/key) ----

    class Builder6388f0FirstPairStreamSeeds(
        val nullScalarWindow: ByteArray, val staticScalarWindow: ByteArray, val nullEntropy11A: ByteArray, val nullAttempts: Int,
        val row0Out4: ByteArray, val row0Out3: ByteArray, val row0Out2: ByteArray, val row0Out1: ByteArray, val row0Out0: ByteArray,
        val row59Out1: ByteArray, val row59Out0: ByteArray,
    )

    fun builder6388f0FirstPair642f60StreamStarts(seeds: Builder6388f0FirstPairStreamSeeds, x2Source: ByteArray? = null): Builder6388f0FirstPair642f60Starts =
        builder6388f0FirstPair642f60StreamStarts(seeds.row0Out0, seeds.row0Out1, seeds.row59Out0, seeds.row59Out1, x2Source)

    fun builder6388f0SeededCaller64RowsFromFirstPairStreamSeeds(
        seeds: Builder6388f0FirstPairStreamSeeds, contextSource: ByteArray? = null, limit: Int = 118, x2Source: ByteArray? = null,
    ): List<Builder6388f0SeededCaller64Row> {
        val starts = builder6388f0FirstPair642f60StreamStarts(seeds, x2Source)
        val row0LowPreimages = Builder6473d0OutputPreimages(seeds.row0Out4, seeds.row0Out3, seeds.row0Out2, seeds.row0Out1, seeds.row0Out0)
        return builder6388f0SeededCaller64Rows(starts, row0LowPreimages, contextSource, limit, x2Source)
    }

    fun deriveFrom6388f0FirstPairStreamSeeds(seeds: Builder6388f0FirstPairStreamSeeds, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray =
        deriveFrom6388f0FirstPairStreamSeeds(seeds, pre63c278Arg0Source, pre63c278Scalar, src4, offset, length)

    fun deriveFrom6388f0FirstPairStreamSeeds(seeds: Builder6388f0FirstPairStreamSeeds, arg0: ByteArray, scalar: ULong, src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10): ByteArray {
        val schedules = builder6388f0Seeded63c278SchedulesFromFirstPairStreamSeeds(seeds, arg0, scalar)
        return deriveFrom6388f0ScheduleLen32Streams(schedules.first.scheduleWords, schedules.second.scheduleWords, src4, offset, length)
    }

    fun phase5RawKeyFrom6388f0FirstPairStreamSeeds(seeds: Builder6388f0FirstPairStreamSeeds, offset: Int = 0): ByteArray =
        Phase5KeySchedule.deriveRawKey(deriveFrom6388f0FirstPairStreamSeeds(seeds, offset = offset, length = 0x10))

    // ============================================================
    // 633fa8 scalar-window tail backbone (tail qwords → e10 words → scalar window)
    // ============================================================

    private fun u32Tbl633fa8Tail(absoluteOffset: Int, t: Tables): UInt {
        if (absoluteOffset >= 0x112588) return u32Tbl(absoluteOffset, t)
        val rel = absoluteOffset - 0x112528
        if (rel < 0 || rel + 4 > t.tailU32LowTables633fa8.size) throw FirstPairSliceException("633fa8 tailU32 OOB $absoluteOffset")
        return rdU32(t.tailU32LowTables633fa8, rel)
    }

    private fun fold633fa8Tail(value: ULong, tableOffset: Int, rounds: Int, t: Tables): ULong {
        var folded = value
        repeat(rounds) {
            val rel = tableOffset - 0x2fe798 + (folded and 0xfuL).toInt() * 8
            if (rel < 0 || rel + 8 > t.tailFoldTables633fa8.size) throw FirstPairSliceException("633fa8 tailFold OOB $tableOffset")
            folded = rdU64(t.tailFoldTables633fa8, rel) + (folded shr 4)
        }
        return folded
    }

    private fun u32Affine633fa8Tail(word: UInt, index: Int, mulTable: Int, addTable: Int, t: Tables): UInt {
        val to = (index * 4) and 0x1c
        return word * u32Tbl633fa8Tail(mulTable + to, t) + u32Tbl633fa8Tail(addTable + to, t)
    }

    private fun tailStreamU64(word: UInt, wordMul: ULong, wordAdd: ULong, foldTable: Int, foldMul: ULong, mixMul: ULong, mixAdd: ULong, t: Tables): ULong {
        val folded = fold633fa8Tail(word.toULong() * wordMul + wordAdd, foldTable, 8, t)
        return folded * foldMul + word.toULong() * mixMul + mixAdd
    }

    private fun convolutionWorkspace633fa8Tail(
        stack: ByteArray, aVecOff: Int, aPrefOff: Int, bVecOff: Int, bPrefOff: Int, outOff: Int, length: Int, outputCount: Int,
        countMul: ULong, countAdd: ULong, productMul: ULong, bPrefixMul: ULong, aPrefixMul: ULong, finalMul: ULong, finalAdd: ULong,
    ) {
        val aVec = ULongArray(length) { rdU64(stack, aVecOff + it * 8) }
        val aPrefix = ULongArray(length) { rdU64(stack, aPrefOff + it * 8) }
        val bVec = ULongArray(length) { rdU64(stack, bVecOff + it * 8) }
        val bPrefix = ULongArray(length) { rdU64(stack, bPrefOff + it * 8) }
        for (index in 0 until outputCount) {
            val low = maxOf(0, index - (length - 1))
            val high = minOf(index, length - 1)
            var productSum = 0uL; var aSum = 0uL; var bSum = 0uL; var count = 0
            if (low <= high) {
                for (bIndex in low..high) productSum += aVec[index - bIndex] * bVec[bIndex]
                aSum = aPrefix[index - low]
                if (index - high - 1 >= 0) aSum -= aPrefix[index - high - 1]
                bSum = bPrefix[high]
                if (low > 0) bSum -= bPrefix[low - 1]
                count = high - low + 1
            }
            var out = count.toULong() * countMul + countAdd
            out += productSum * productMul
            out += bSum * bPrefixMul
            out += aSum * aPrefixMul
            out = out * finalMul + finalAdd
            wrU64(stack, outOff + index * 8, out)
        }
    }

    fun builder633fa8TailQwordsFromSources(words3ab0: UIntArray, words3120: UIntArray, words2dfc: UIntArray, seed3110: ULong): ULongArray {
        require(words3ab0.size == 20 && words3120.size == 20 && words2dfc.size == 20)
        val t = Tables.shared
        val stack = ByteArray(0x4300)

        // stream A → 0x3f40 / prefix 0x3cf0
        var word = words3ab0[0] * 0xc365675bu + 0xe8f087b3u
        var value = tailStreamU64(word, 0x39629fb00ae1a583uL, 0xc87e38ff2ae3bb2duL, 0x2fe798, 0xd0779b0b00000000uL, 0x7168c55d8932925fuL, 0x9f10057a9662ab2duL, t)
        wrU64(stack, 0x3f40, value); wrU64(stack, 0x3cf0, value)
        var prefix = value
        for (index in 1 until 20) {
            word = u32Affine633fa8Tail(words3ab0[index], index, 0x11fd08, 0x11fd28, t) * 0x6ebad499u + 0x8b060038u
            value = tailStreamU64(word, 0x39629fb00ae1a583uL, 0xc87e38ff2ae3bb2duL, 0x2fe798, 0xd0779b0b00000000uL, 0x7168c55d8932925fuL, 0x9f10057a9662ab2duL, t)
            wrU64(stack, 0x3f40 + index * 8, value)
            prefix += value
            wrU64(stack, 0x3cf0 + index * 8, prefix)
        }

        // stream B → 0x3e10 / prefix 0x3bd0
        word = words3120[0] * 0x21753b73u + 0x9f972fa4u
        value = tailStreamU64(word, 0xc16bd9358bd641f1uL, 0xdbd59c6303e46229uL, 0x2fe818, 0xe919ac4d00000000uL, 0x0eb018d832b73e83uL, 0x1f4e35decd254a8buL, t)
        wrU64(stack, 0x3e10, value); wrU64(stack, 0x3bd0, value)
        prefix = value
        for (index in 1 until 20) {
            word = u32Affine633fa8Tail(words3120[index], index, 0x112528, 0x112548, t) * 0x740d5673u + 0xf3b4a3bcu
            value = tailStreamU64(word, 0xc16bd9358bd641f1uL, 0xdbd59c6303e46229uL, 0x2fe818, 0xe919ac4d00000000uL, 0x0eb018d832b73e83uL, 0x1f4e35decd254a8buL, t)
            wrU64(stack, 0x3e10 + index * 8, value)
            prefix += value
            wrU64(stack, 0x3bd0 + index * 8, prefix)
        }

        convolutionWorkspace633fa8Tail(stack, 0x3e10, 0x3bd0, 0x3f40, 0x3cf0, 0x4080, 20, 42,
            0x88edcb9fcc5a504fuL, 0xc50c4cfe6b90cc32uL, 0xb280f1fcde620b25uL, 0x24cc8b7736fa66cfuL, 0x512ce98be108b3a5uL, 0xe8cb5b6d2f40c331uL, 0xeb47abb56d203e7duL)

        // stream C → overwrites 0x3f40 region
        for (index in 0 until 20) {
            word = u32Affine633fa8Tail(words2dfc[index], index, 0x11b268, 0x120e48, t) * 0x4890e04fu + 0xc2cec971u
            value = tailStreamU64(word, 0xe2d3ea4512d167e7uL, 0xa7c876b324afde01uL, 0x2fe898, 0xc4e79ba300000000uL, 0xf5ea48539d50faebuL, 0x37ffe0ce46814927uL, t)
            wrU64(stack, 0x3f40 + index * 8, value)
        }

        val q = ULongArray(20) { rdU64(stack, 0x3f40 + it * 8) }
        var x30 = rdU64(stack, 0x4080)
        val seedA = seed3110 * 0xac33be2f37df9899uL + 0xf586b9c725fc2655uL
        val seedB = seed3110 * 0xc460e481253db509uL + 0x5642aeb8585a52ebuL

        var byteOffset = 0
        while (byteOffset < 0xb0) {
            val position = 0x4080 + byteOffset
            var x10 = x30 * seedA + seedB
            var folded = fold633fa8Tail(x10 * 0x09883223fa4660eduL + 0x2d97cba42b4b302fuL, 0x2fe918, 7, t)
            x10 = x10 * 0x9c92333d4c3638c9uL + folded * 0x718df58330000000uL + 0xdb9c322f9570a279uL

            val x7 = x10 * 0x06192264fc57feafuL + 0xe244b4f2265375bfuL
            val x21 = x10 * 0x3fd0fde8a99b1d3cuL + 0x7bd7d5be27b1d17cuL
            val x3 = x7 * q[8] + x21
            x30 = x7 * q[0] + x21 + x30
            val x4 = x7 * q[9] + x21

            folded = fold633fa8Tail(x30 * 0xa5ba351ba23facf5uL + 0x0720c6fcb580eff2uL, 0x2fe998, 7, t)
            var x12 = folded * 0x9549f71510a8f0e7uL + 0x2d3bf7a5dd39f0abuL
            folded = fold633fa8Tail(x12 * 0xead4735c0bc5924duL + 0x73beb11d9159837cuL, 0x2fea18, 9, t)
            var x8Mix = x12 * 0x7794ebcd6781608duL + folded * 0xafc58bf000000000uL

            val old08 = rdU64(stack, position + 0x08)
            val old10 = rdU64(stack, position + 0x10)
            var x13 = x7 * q[1] + x21 + old08
            val x11 = x7 * q[2] + x21 + old10
            wrU64(stack, position, x30)
            wrU64(stack, position + 0x08, x13)

            val acc13 = x7 * q[13] + x21
            val old18 = rdU64(stack, position + 0x18)
            val old20 = rdU64(stack, position + 0x20)
            var x14 = x7 * q[3] + x21 + old18
            val x15 = x7 * q[4] + x21 + old20

            val old28 = rdU64(stack, position + 0x28)
            val old30 = rdU64(stack, position + 0x30)
            val x16 = x7 * q[5] + x21 + old28
            var x17 = x7 * q[6] + x21 + old30
            wrU64(stack, position + 0x18, x14)
            wrU64(stack, position + 0x20, x15)

            val old38 = rdU64(stack, position + 0x38)
            val old40 = rdU64(stack, position + 0x40)
            var acc15 = x7 * q[14] + x21
            val x1 = x7 * q[7] + x21 + old38
            val x16b = x3 + old40
            wrU64(stack, position + 0x28, x16)
            wrU64(stack, position + 0x30, x17)

            val old48 = rdU64(stack, position + 0x48)
            val old50 = rdU64(stack, position + 0x50)
            val acc0 = x7 * q[15] + x21
            x14 = x4 + old48
            val acc2 = x7 * q[10] + x21
            x17 = acc2 + old50
            wrU64(stack, position + 0x38, x1)
            wrU64(stack, position + 0x40, x16b)
            wrU64(stack, position + 0x48, x14)
            wrU64(stack, position + 0x50, x17)

            val old58 = rdU64(stack, position + 0x58)
            val old60 = rdU64(stack, position + 0x60)
            val acc12 = x7 * q[11] + x21
            val acc30 = x7 * q[12] + x21
            val acc14 = x7 * q[16] + x21
            x12 = acc12 + old58
            x17 = acc30 + old60
            val acc1 = x7 * q[17] + x21
            wrU64(stack, position + 0x58, x12)
            wrU64(stack, position + 0x60, x17)

            val old68 = rdU64(stack, position + 0x68)
            val old70 = rdU64(stack, position + 0x70)
            x13 = acc13 + old68
            x12 = acc15 + old70
            val acc16 = x7 * q[18] + x21
            wrU64(stack, position + 0x68, x13)
            wrU64(stack, position + 0x70, x12)

            val old78 = rdU64(stack, position + 0x78)
            val old80 = rdU64(stack, position + 0x80)
            acc15 = x7 * q[19] + x21
            x12 = acc0 + old78
            x13 = acc14 + old80
            wrU64(stack, position + 0x78, x12)
            wrU64(stack, position + 0x80, x13)

            x8Mix = x8Mix * 0x56c495ec086d9247uL + rdU64(stack, position + 0x08)

            val old88 = rdU64(stack, position + 0x88)
            val old90 = rdU64(stack, position + 0x90)
            x12 = acc1 + old88
            x14 = acc16 + old90
            wrU64(stack, position + 0x88, x12)
            wrU64(stack, position + 0x90, x14)

            val old98 = rdU64(stack, position + 0x98)
            x12 = acc15 + old98
            wrU64(stack, position + 0x98, x12)

            x30 = x8Mix + 0xc387faf5615fb2e3uL
            wrU64(stack, position + 0x08, x30)
            wrU64(stack, position + 0x10, x11)
            byteOffset += 8
        }

        return ULongArray(20) { rdU64(stack, 0x4130 + it * 8) }
    }

    fun builder633fa8E10WordsFromTailQwords(tailQwords: ULongArray): UIntArray {
        require(tailQwords.size == 20)
        val t = Tables.shared
        var carry = 0x7f9e71176c43f336uL
        val out = UIntArray(20)
        for (index in tailQwords.indices) {
            val source = tailQwords[index] * 0xa44fd620a45fddc7uL
            carry = carry * 0x8b3babe0304f96f9uL + source
            carry += 0x12e00771bb9547afuL

            val foldedSeed = carry * 0x039ae28b51354965uL + 0x248e5dc60fc0f4fbuL
            var w = (carry and 0xffffffffuL).toUInt() * 0x4a018c3bu
            val folded7 = fold633fa8Tail(foldedSeed, 0x2fea98, 7, t)
            w = w + ((folded7 and 0x0fuL).toUInt() shl 28) + 0x79d84f1bu
            val folded16 = fold633fa8Tail(folded7, 0x2fea98, 9, t)

            val to = (index * 4) and 0x1c
            w = w * u32Tbl(0x118e08 + to, t) + u32Tbl(0x1229c8 + to, t)
            out[index] = w

            carry = folded7 * 0xd310088be2b9ce15uL + folded16 * 0xd4631eb000000000uL + 0x02f149c1c6520051uL
        }
        return out
    }

    fun builder633fa8ScalarWindowFromE10Words(words: UIntArray): ByteArray {
        require(words.size == 20)
        val t = Tables.shared
        val out = ByteArray(70)
        var accumulator = 0uL
        var bitCount = 0
        var outIndex = 0
        for (index in words.indices) {
            val to = (index * 4) and 0x1c
            var value = words[index] * u32Tbl(0x121808 + to, t) + u32Tbl(0x11f508 + to, t)
            value = value * 0x37c0c559u + 0xfa73673bu
            accumulator = accumulator xor (value.toULong() shl bitCount)
            bitCount += 28
            while (bitCount > 16 && outIndex < 70 - 1) {
                out[outIndex] = (accumulator and 0xffuL).toByte()
                accumulator = accumulator shr 8
                outIndex += 1
                bitCount -= 8
            }
        }
        if (bitCount >= 1 && outIndex < 70 - 1) out[outIndex] = (accumulator and 0xffuL).toByte()
        return out
    }

    // ============================================================
    // 6388f0 low-seed family (static path: entrySource → prelude → boundary → scalar window)
    // ============================================================

    private fun vm641fcc(magic: Long, src: ByteArray, t: Tables): ByteArray {
        val progOff = (magic and 0x3fffffL).toInt()
        val primer = ((magic ushr 22) and 0x3fffL).toInt()
        val count = ((magic ushr 36) and 0x3fffL).toInt()
        require(primer >= 2) { "vm641fcc primer $primer" }
        val prog = checkedSlice(t.prog638840, progOff, primer + count + 3)
        var state = 0
        for (i in 0 until primer) state = step16Masked(state, src[i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
        val out = ByteArray(count + 3)
        for (i in 0 until count) {
            val srcPos = primer + i
            state = step16Masked(state, src[srcPos].toInt() and 0xff, prog[srcPos].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        val tailProg = primer + count
        for (i in 0 until 3) {
            state = step16Full(state, src[2 + i].toInt() and 0xff, prog[tailProg + i].toInt() and 0xff, t)
            out[count + i] = (state and 7).toByte()
        }
        return out
    }

    private fun lowSeedStaticBlock(libOffset: Int, t: Tables): ByteArray = checkedSlice(t.lowSeedStatics6388f0, libOffset - 0x2f4d28, 0x10a)
    private fun lowLoopStaticBlock(libOffset: Int, byteCount: Int, t: Tables): ByteArray = checkedSlice(t.lowLoopStatics6388f0, libOffset - 0x2fe600, byteCount)
    private fun lowLoopStaticByte(libOffset: Int, t: Tables): Int = lowLoopStaticBlock(libOffset, 1, t)[0].toInt() and 0xff

    private class LowSeedPhaseSpec(val phaseMagic: Long, val auxMagics: LongArray, val e10Magics: LongArray, val e10Markers: IntArray, val bd0Magic: Long, val staticOffset: Int, val unaryMagic: Long, val f40Magics: LongArray)
    private val lowSeedE10SourceShifts = intArrayOf(4, 8, 16, 32, 64, 128, 256)

    private val lowSeedPhase1Spec = LowSeedPhaseSpec(
        0x10a000002563L,
        longArrayOf(0x10a0000076a7L, 0x10a000002de5L, 0x10a000007af9L, 0x10a000007e6bL, 0x10a0000068a9L, 0x10a0000062e7L, 0x10a000007025L),
        longArrayOf(0x10a0000008dcL, 0x10a00000448cL, 0x10a000000000L, 0x10a000007493L, 0x10a0000039c1L, 0x10a000000b56L, 0x10a000005648L),
        intArrayOf(3, 2, 3, 3, 7, 6, 3), 0x10a000006abdL, 0x2f4d28, 0x000c107000c03253L,
        longArrayOf(0x0410006041004174L, 0x040000a040002bb7L, 0x03e001203e001e19L, 0x03a002203a005ba1L, 0x0320042032003f90L, 0x02200820220066f1L, 0x0020102002001526L))
    private val lowSeedPhase2Spec = LowSeedPhaseSpec(
        0x10a0000046c4L,
        longArrayOf(0x10a0000077b1L, 0x10a0000038b7L, 0x10a000001adeL, 0x10a00000727fL, 0x10a0000069b3L, 0x10a000007c03L, 0x10a00000201bL),
        longArrayOf(0x10a000006f1bL, 0x10a00000553eL, 0x10a000005d93L, 0x10a000001648L, 0x10a000002459L, 0x10a000002aadL, 0x10a0000005d0L),
        intArrayOf(3, 1, 5, 7, 6, 0, 7), 0x10a000003360L, 0x2f4e32, 0x000c107000c01105L,
        longArrayOf(0x04100060410052faL, 0x040000a040001c0fL, 0x03e001203e0028abL, 0x03a002203a007f75L, 0x0320042032002275L, 0x0220082022005168L, 0x0020102002002799L))
    private val lowSeedPhase3Spec = LowSeedPhaseSpec(
        0x10a0000048faL,
        longArrayOf(0x10a0000045baL, 0x10a000000d8cL, 0x10a0000019d4L, 0x10a000003586L, 0x10a0000037adL, 0x10a00000759dL, 0x10a000007389L),
        longArrayOf(0x10a000004382L, 0x10a00000010aL, 0x10a000005ec1L, 0x10a000003c3bL, 0x10a000007153L, 0x10a000002125L, 0x10a00000346aL),
        intArrayOf(3, 3, 0, 3, 1, 0, 5), 0x10a00000505eL, 0x2f4f3c, 0x000c107000c079daL,
        longArrayOf(0x0410006041005993L, 0x040000a040001212L, 0x03e001203e0006daL, 0x03a002203a0060e5L, 0x0320042032004c30L, 0x022008202200655fL, 0x0020102002005752L))

    private fun lowSeedE10SourceFromAB0(marker: Int, shift: Int, ab0: ByteArray): ByteArray {
        val out = ByteArray(0x10a)
        out[shift - 1] = marker.toByte()
        val copyCount = 0x10a - shift
        if (copyCount > 0) System.arraycopy(ab0, 0, out, shift, copyCount)
        return out
    }

    private fun lowSeedPhaseFromCF0Seed(spec: LowSeedPhaseSpec, seedCF0: ByteArray, t: Tables): ByteArray {
        val count = spec.auxMagics.size
        val staticBlock = lowSeedStaticBlock(spec.staticOffset, t)
        var cf0 = seedCF0
        for (index in 0 until count) {
            val bd0 = vm638840(spec.bd0Magic, cf0, staticBlock, t)
            val ab0 = vm641fcc(spec.unaryMagic, bd0, t)
            val e10Source = lowSeedE10SourceFromAB0(spec.e10Markers[index], lowSeedE10SourceShifts[index], ab0)
            val e10 = vm638840(spec.e10Magics[index], e10Source, e10Source, t)
            val f40 = vm6420d8(spec.f40Magics[index], ab0, ab0, t)
            val aux = vm638840(spec.auxMagics[index], e10, f40, t)
            cf0 = vm638840(spec.phaseMagic, cf0, aux, t)
        }
        return cf0
    }

    internal class LowSeedCF0Seeds(val phase1: ByteArray, val phase2: ByteArray, val phase3: ByteArray)
    internal class LowSeedTailPair(val left: ByteArray, val right: ByteArray)
    class Builder6388f0LowSeedLoopResult(val final6377f0: ByteArray, val scheduleWords: UIntArray)
    class Builder633fa8TailBoundary(val words3ab0: UIntArray, val words3120: UIntArray, val words2dfc: UIntArray, val seed3110: ULong, val preludeSource: ByteArray)

    private val invariantWords3120 = uintArrayOf(
        0xb33842d7u, 0x7b6ba784u, 0xa2f90f36u, 0xde5e2ad7u, 0x3c3537a9u, 0x81d564f6u, 0x339ab4a2u, 0x999de03bu, 0x56c13b42u, 0xff14a487u,
        0x5a31640cu, 0xc3f85236u, 0x3c1dc79eu, 0x58a8d4a6u, 0x541cb00eu, 0x63323fcdu, 0x1aa54a16u, 0x01f1b661u, 0x5a31640cu, 0xc3f85236u)
    private val invariantWords2dfc = uintArrayOf(
        0x9bed19fdu, 0xc70a4d0fu, 0x8257d22bu, 0xe2fafcb3u, 0x02c77d20u, 0xb5ed0efau, 0x878c1b06u, 0x4bd92d7du, 0x21c6944fu, 0xd3ec5d2fu,
        0x876fda86u, 0x37f3e22au, 0x3cfcd7ceu, 0xabdc16ebu, 0x84ad2f7du, 0x4bd92d7du, 0xf647adceu, 0xaa7b701eu, 0x876fda86u, 0x37f3e22au)

    fun builder6388f0LowSeedCF0SeedsFromEntrySource(entrySource: ByteArray): LowSeedCF0Seeds {
        val t = Tables.shared
        val entryHead = entrySource.copyOfRange(0, 0x10a)
        val entryTail = entrySource.copyOfRange(0x10a, 0x214)
        val pre2S898 = vm638840(0x10a000001764L, entryHead, entryHead, t)
        val pre2S78E = vm638840(0x10a000000fe9L, entryTail, entryTail, t)
        val phase1SeedCF0 = vm641fcc(0x000c107000c03d57L, pre2S78E, t)
        val phase1 = lowSeedPhaseFromCF0Seed(lowSeedPhase1Spec, phase1SeedCF0, t)
        val pre2Static = lowSeedStaticBlock(0x2f4d28, t)
        val pre2BD0 = vm638840(0x10a000006abdL, phase1, pre2Static, t)
        val pre2S684 = vm638840(0x10a000005fdbL, pre2BD0, pre2BD0, t)
        val pre2S57A = vm638840(0x10a00000141cL, pre2S898, pre2S684, t)
        val preS898 = vm638840(0x10a0000003bcL, pre2S78E, pre2S78E, t)
        val preS78E = vm638840(0x10a000006bc7L, pre2S57A, pre2S57A, t)
        val phase2SeedCF0 = vm641fcc(0x000c107000c05886L, preS78E, t)
        val phase2 = lowSeedPhaseFromCF0Seed(lowSeedPhase2Spec, phase2SeedCF0, t)
        val preStatic = lowSeedStaticBlock(0x2f4e32, t)
        val preBD0 = vm638840(0x10a000003360L, phase2, preStatic, t)
        val prevS684 = vm638840(0x10a0000004c6L, preBD0, preBD0, t)
        val prevS57A = vm638840(0x10a00000141cL, preS898, prevS684, t)
        val seedS78E = vm638840(0x10a000006bc7L, prevS57A, prevS57A, t)
        val phase3SeedCF0 = vm641fcc(0x000c107000c036a0L, seedS78E, t)
        return LowSeedCF0Seeds(phase1SeedCF0, phase2SeedCF0, phase3SeedCF0)
    }

    private fun lowSeedTailPairFromSlotState(preS898: ByteArray, preS78E: ByteArray, preBD0: ByteArray, tailBD0: ByteArray, t: Tables): LowSeedTailPair {
        val prevS684 = vm638840(0x10a0000004c6L, preBD0, preBD0, t)
        val prevS57A = vm638840(0x10a00000141cL, preS898, prevS684, t)
        val seedS898 = vm638840(0x10a0000003bcL, preS78E, preS78E, t)
        val seedS78E = vm638840(0x10a000006bc7L, prevS57A, prevS57A, t)
        val tailS684 = vm638840(0x10a000004a08L, tailBD0, tailBD0, t)
        val tailS57A = vm638840(0x10a00000141cL, seedS898, tailS684, t)
        val left = vm638840(0x10a0000003bcL, seedS78E, seedS78E, t)
        val right = vm638840(0x10a000006bc7L, tailS57A, tailS57A, t)
        return LowSeedTailPair(left, right)
    }

    private fun lowSeedTailPairFromEntryAndCF0(entrySource: ByteArray, pre2CF0: ByteArray, preCF0: ByteArray, tailCF0: ByteArray, t: Tables): LowSeedTailPair {
        val entryHead = entrySource.copyOfRange(0, 0x10a)
        val entryTail = entrySource.copyOfRange(0x10a, 0x214)
        val pre2S898 = vm638840(0x10a000001764L, entryHead, entryHead, t)
        val pre2S78E = vm638840(0x10a000000fe9L, entryTail, entryTail, t)
        val pre2Static = lowSeedStaticBlock(0x2f4d28, t)
        val preStatic = lowSeedStaticBlock(0x2f4e32, t)
        val tailStatic = lowSeedStaticBlock(0x2f4f3c, t)
        val pre2BD0 = vm638840(0x10a000006abdL, pre2CF0, pre2Static, t)
        val pre2S684 = vm638840(0x10a000005fdbL, pre2BD0, pre2BD0, t)
        val pre2S57A = vm638840(0x10a00000141cL, pre2S898, pre2S684, t)
        val preS898 = vm638840(0x10a0000003bcL, pre2S78E, pre2S78E, t)
        val preS78E = vm638840(0x10a000006bc7L, pre2S57A, pre2S57A, t)
        val preBD0 = vm638840(0x10a000003360L, preCF0, preStatic, t)
        val tailBD0 = vm638840(0x10a00000505eL, tailCF0, tailStatic, t)
        return lowSeedTailPairFromSlotState(preS898, preS78E, preBD0, tailBD0, t)
    }

    fun builder6388f0LowSeedTailPairFromEntrySource(entrySource: ByteArray): LowSeedTailPair {
        val t = Tables.shared
        val seeds = builder6388f0LowSeedCF0SeedsFromEntrySource(entrySource)
        val phase1 = lowSeedPhaseFromCF0Seed(lowSeedPhase1Spec, seeds.phase1, t)
        val phase2 = lowSeedPhaseFromCF0Seed(lowSeedPhase2Spec, seeds.phase2, t)
        val phase3 = lowSeedPhaseFromCF0Seed(lowSeedPhase3Spec, seeds.phase3, t)
        return lowSeedTailPairFromEntryAndCF0(entrySource, phase1, phase2, phase3, t)
    }

    fun builder6388f0LowSeedTailStageFromPair(pair: LowSeedTailPair): ByteArray =
        vm638840(0x10a000007d1fL, pair.left, pair.right, Tables.shared)

    fun builder6388f0LowSeedPreludeSourceFromTailStage(tailStage: ByteArray): ByteArray {
        val t = Tables.shared
        val stage = vm638840(0x10a0000018b4L, tailStage, tailStage, t)
        return vm638840(0x10a000000c72L, stage, stage, t)
    }

    fun builder6388f0LowSeedBlocksFromPreludeSource(preludeSource: ByteArray): ByteArray {
        val t = Tables.shared
        val expanded = vm6420d8(0x810a000003acbL, preludeSource, preludeSource, t)
        val out = ByteArray(20 * 0x10)
        for (index in 0 until 19) {
            val block = expanded.copyOfRange(index * 0x0e, index * 0x0e + 0x10)
            System.arraycopy(vm638840(0x10000005fcbL, block, block, t), 0, out, index * 0x10, 0x10)
        }
        val staticBlock = lowLoopStaticBlock(0x2fe720, 0x10, t)
        System.arraycopy(vm638840(0x10000005fcbL, staticBlock, staticBlock, t), 0, out, 19 * 0x10, 0x10)
        return out
    }

    fun builder6388f0LowSeedLoopFromBlocks(seedBlocks: ByteArray): Builder6388f0LowSeedLoopResult {
        require(seedBlocks.size == 20 * 0x10)
        val t = Tables.shared
        val scheduleWords = UIntArray(20)
        var final6377f0 = ByteArray(0)
        for (outerIndex in 0 until 20) {
            val lane = outerIndex and 7
            var cLane = lowLoopStaticBlock(0x2fe730, 0x10, t) + byteArrayOf(0x05, 0x04)
            val eSource = lowLoopStaticBlock(0x2fe600 + 18 * lane, 18, t)
            var eLane = vm638840(0x12000006ef9L, eSource, eSource, t)
            val block = seedBlocks.copyOfRange(outerIndex * 0x10, outerIndex * 0x10 + 0x10)
            val dLane = vm6420d8(0x801000000654dL, block, block, t)
            var bLane = vm638840(0x12000007d0dL, eLane, eLane, t)

            var aLane = ByteArray(18)
            var tLane = ByteArray(18)
            repeat(28) {
                val fLane = vm638840(0x12000000398L, dLane, cLane, t)
                val aSource = lowLoopStaticBlock(0x2fe742, 18, t)
                aLane = vm638840(0x12000006ddbL, aSource, fLane, t)
                tLane = vm638840(0x12000001752L, eLane, aLane, t)
                bLane = vm638840(0x12000002dd3L, bLane, tLane, t)
                eLane = vm638840(0x12000007ae7L, eLane, eLane, t)
                cLane = vm638840(0x12000006897L, cLane, cLane, t)
            }
            final6377f0 = tLane
            val fLane = vm638840(0x12000003241L, bLane, eLane, t)
            val dSource = lowLoopStaticBlock(0x2fe690 + 18 * lane, 18, t)
            val postDLane = vm638840(0x120000045a8L, fLane, dSource, t)
            var packELane = vm641fcc(0xc000f000c01bfaL, postDLane, t)

            val packedLane = aLane.copyOf()
            for (k in 0 until 4) packedLane[k] = 0
            var shift = 32
            for (packIndex in 0 until 8) {
                val cWord = vm638840(0x4000004a04L, packELane, packELane, t)
                if (shift >= 5) packELane = vm6420d8(0x8010000800350L, packELane, packELane, t)
                val bWord = vm638840(0x4000002271L, cWord, cWord, t)
                val selected = (bWord[2].toInt() and 0xff) xor ((bWord[3].toInt() and 0xff) shl 3)
                val packed = lowLoopStaticByte(0x2fe754 + selected, t)
                var nibble = if (packIndex and 1 == 0) (packed and 0x0f) else (packed ushr 4)
                if (shift < 4) { val mask = if (shift == 0) 0 else (1 shl shift) - 1; nibble = nibble and mask }
                val byteIndex = packIndex shr 1
                if (packIndex and 1 == 0) packedLane[byteIndex] = nibble.toByte()
                else packedLane[byteIndex] = (packedLane[byteIndex].toInt() xor (nibble shl 4)).toByte()
                shift = maxOf(shift - 4, 0)
            }
            scheduleWords[outerIndex] = rdU32(packedLane, 0)
        }
        return Builder6388f0LowSeedLoopResult(final6377f0, scheduleWords)
    }

    fun builder633fa8StaticPreludeSourceFromEntrySource(entrySource: ByteArray): ByteArray {
        val pair = builder6388f0LowSeedTailPairFromEntrySource(entrySource)
        val tailStage = builder6388f0LowSeedTailStageFromPair(pair)
        return builder6388f0LowSeedPreludeSourceFromTailStage(tailStage)
    }

    fun builder633fa8TailBoundaryFromPreludeSource(preludeSource: ByteArray): Builder633fa8TailBoundary {
        val seedBlocks = builder6388f0LowSeedBlocksFromPreludeSource(preludeSource)
        val loop = builder6388f0LowSeedLoopFromBlocks(seedBlocks)
        return Builder633fa8TailBoundary(loop.scheduleWords, invariantWords3120, invariantWords2dfc, 0xb6ccf02833a9825euL, preludeSource)
    }

    fun builder633fa8StaticTailBoundaryFromEntrySource(entrySource: ByteArray): Builder633fa8TailBoundary =
        builder633fa8TailBoundaryFromPreludeSource(builder633fa8StaticPreludeSourceFromEntrySource(entrySource))

    fun builder633fa8ScalarWindowFromPreludeSource(preludeSource: ByteArray): ByteArray {
        val b = builder633fa8TailBoundaryFromPreludeSource(preludeSource)
        val qwords = builder633fa8TailQwordsFromSources(b.words3ab0, b.words3120, b.words2dfc, b.seed3110)
        return builder633fa8ScalarWindowFromE10Words(builder633fa8E10WordsFromTailQwords(qwords))
    }

    fun builder633fa8StaticScalarWindowFromEntrySource(entrySource: ByteArray): ByteArray =
        builder633fa8ScalarWindowFromPreludeSource(builder633fa8StaticPreludeSourceFromEntrySource(entrySource))

    // ============================================================
    // 633fa8 NULL scalar-window path (runtime: on-device entropy → phone-ephemeral scalar)
    // ============================================================

    private class Rejected633fa8NullEntropy : RuntimeException()
    class Builder633fa8NullEntrySources(val prologueSource: ByteArray, val check1SourceWords: UIntArray, val check2SourceWords: UIntArray)
    class Builder633fa8NullInitialResult(val maskedEntropy: ByteArray, val cf0: ByteArray, val e10: ByteArray, val seedInputs: ByteArray, val seedBlocks: ByteArray)
    class Builder633fa8NullFirstLoopResult(val finalTLane: ByteArray, val scheduleWords: UIntArray)
    class Builder633fa8NullScheduleAcceptance(val firstOK: Boolean, val secondOK: Boolean)
    class Builder633fa8NullPostAcceptResult(val blocks4080: ByteArray, val blocks3f40: ByteArray)
    class Builder633fa8NullScalarResult(val scalarWindow: ByteArray, val entropy11A: ByteArray, val attempts: Int)

    private val nullEntryBitsChecksSource = re.abbot.librecr.protocol.hexToBytes(
        "3674f8f8a81c394e2bca21f938be42b1adbc94923891e2d38ee57c2d131dcebb" +
        "6eed185b2fe5d82f9543c721bdf818eb782dd2545d9b6429daaa6d5b725db614" +
        "4b8b6d5dca64a99a7565cb64a9baa66599b5688b34dd9aaadc9a354d53a2cd8a" +
        "756bca955b56b42bca12e1343551a11412fbcb2ecd59982c841bdca6eeda33bd" +
        "5e2cf8e2f1b468845576104cfaf7f8ceecfa7a15262ed5f6fa9bd9d442e12e97" +
        "ec15c1cb4c3ec1ec2881104cfaf7f8ceecfa7a15262ed5f6fa9b8bb0d0b3c47a" +
        "1c6cf95016b01676804ff8491d5e0e08e8c3b0504bc066ef57b66fbe719164d2" +
        "19086a9310bf190e20a7c27976c5579249c17bedcf2166ef57b6453b9c865799" +
        "a2246a9310bf190e20a7")

    private fun unpack3BitStream5bdd14(source: ByteArray, offset: Int, count: Int): Pair<ByteArray, Int> {
        var pointer = offset
        var bitOffset = 0
        val out = ByteArray(count)
        for (n in 0 until count) {
            val currentBit = bitOffset and 0xff
            when {
                currentBit == 8 -> { pointer += 1; out[n] = (source[pointer].toInt() and 7).toByte(); bitOffset = 3 }
                currentBit == 0 -> { out[n] = (source[pointer].toInt() and 7).toByte(); bitOffset = 3 }
                currentBit <= 5 -> { out[n] = ((source[pointer].toInt() and 0xff) ushr currentBit and 7).toByte(); bitOffset = currentBit + 3 }
                else -> {
                    val spanBits = currentBit - 5
                    val low = (source[pointer].toInt() and 0xff) ushr currentBit
                    val high = (source[pointer + 1].toInt() and 0xff) and ((1 shl spanBits) - 1)
                    out[n] = ((low or (high shl (8 - currentBit))) and 7).toByte()
                    pointer += 1
                    bitOffset = spanBits
                }
            }
        }
        return Pair(out, pointer + 1)
    }

    private fun nullTableBlock(libOffset: Int, byteCount: Int, t: Tables): ByteArray = checkedSlice(t.nullTables633fa8, libOffset - 0x2fd1f1, byteCount)
    private fun nullNibbleByte(libOffset: Int, t: Tables): Int = checkedSlice(t.nullNibble633fa8, libOffset - 0x303a14, 1)[0].toInt() and 0xff
    private fun u32TableWord633fa8Null(absoluteOffset: Int, t: Tables): UInt = rdU32(t.nullTables633fa8, absoluteOffset - 0x2fd1f1)
    private fun fold633fa8NullCheck32(value: UInt, tableOffset: Int, rounds: Int, t: Tables): UInt {
        var folded = value
        repeat(rounds) { folded = u32TableWord633fa8Null(tableOffset + (folded and 0x0fu).toInt() * 4, t) + (folded shr 4) }
        return folded
    }
    private fun expand3BitPairTableRow633fa8Null(raw9: ByteArray): ByteArray {
        val out = ByteArray(18)
        for (i in 0 until 9) { out[i * 2] = (raw9[i].toInt() and 7).toByte(); out[i * 2 + 1] = ((raw9[i].toInt() ushr 3) and 7).toByte() }
        return out
    }

    fun builder633fa8NullEntrySourcesFromInvariantEntry(): Builder633fa8NullEntrySources {
        val (prologue, cursor) = unpack3BitStream5bdd14(nullEntryBitsChecksSource, 0, 0x11a)
        val check1 = UIntArray(20) { rdU32(nullEntryBitsChecksSource, cursor + it * 4) }
        val check2 = UIntArray(20) { rdU32(nullEntryBitsChecksSource, cursor + 80 + it * 4) }
        return Builder633fa8NullEntrySources(prologue, check1, check2)
    }

    fun builder633fa8NullInitialFromEntropy(entropy11A: ByteArray, prologueSource: ByteArray): Builder633fa8NullInitialResult {
        require(entropy11A.size == 0x11a)
        val t = Tables.shared
        val maskedEntropy = ByteArray(0x11a) { (entropy11A[it].toInt() and 7).toByte() }
        val prologue = prologueSource.copyOf(0x11a)
        val cf0 = vm638840(0x11a000000236L, maskedEntropy, prologue, t)
        val e10 = vm638840(0x11a0000047e0L, cf0, cf0, t)
        val seedInputs = ByteArray(20 * 0x10)
        for (index in 0 until 20) System.arraycopy(e10, index * 0x0e, seedInputs, index * 0x10, 0x10)
        val seedBlocks = ByteArray(20 * 0x10)
        for (index in 0 until 20) {
            val block = seedInputs.copyOfRange(index * 0x10, index * 0x10 + 0x10)
            System.arraycopy(vm638840(0x1000000725dL, block, block, t), 0, seedBlocks, index * 0x10, 0x10)
        }
        return Builder633fa8NullInitialResult(maskedEntropy, cf0, e10, seedInputs, seedBlocks)
    }

    fun builder633fa8NullFirstLoopFromBlocks(seedBlocks: ByteArray): Builder633fa8NullFirstLoopResult {
        require(seedBlocks.size == 20 * 0x10)
        val t = Tables.shared
        val scheduleWords = UIntArray(20)
        var finalTLane = ByteArray(0)
        for (outerIndex in 0 until 20) {
            val lane = outerIndex and 7
            var cLane = nullTableBlock(0x2fd311, 0x10, t) + byteArrayOf(0x05, 0x05)
            val eSource = nullTableBlock(0x2fd1f1 + 18 * lane, 18, t)
            var eLane = vm638840(0x12000000376L, eSource, eSource, t)
            val block = seedBlocks.copyOfRange(outerIndex * 0x10, outerIndex * 0x10 + 0x10)
            val dLane = vm6420d8(0x8010000002447L, block, block, t)
            var bLane = vm638840(0x120000010f3L, eLane, eLane, t)

            var aLane = ByteArray(18)
            var tLane = ByteArray(18)
            repeat(28) {
                val fLane = vm638840(0x12000004596L, dLane, cLane, t)
                val aSource = nullTableBlock(0x2fd323, 18, t)
                aLane = vm638840(0x12000007141L, aSource, fLane, t)
                tLane = vm638840(0x12000008199L, eLane, aLane, t)
                bLane = vm638840(0x1200000726dL, bLane, tLane, t)
                eLane = vm638840(0x12000000eabL, eLane, eLane, t)
                cLane = vm638840(0x12000005026L, cLane, cLane, t)
            }
            finalTLane = tLane
            val fLane = vm638840(0x12000003e64L, bLane, eLane, t)
            val dSource = nullTableBlock(0x2fd281 + 18 * lane, 18, t)
            val postDLane = vm638840(0x12000001be8L, fLane, dSource, t)
            var packELane = vm641fcc(0x0c00f000c079c5L, postDLane, t)

            val packedLane = aLane.copyOf()
            for (k in 0 until 4) packedLane[k] = 0
            var shift = 32
            for (packIndex in 0 until 8) {
                val cWord = vm638840(0x4000000a28L, packELane, packELane, t)
                if (shift >= 5) packELane = vm6420d8(0x8010000806883L, packELane, packELane, t)
                val bWord = vm638840(0x400000186eL, cWord, cWord, t)
                val selected = (bWord[2].toInt() and 0xff) xor ((bWord[3].toInt() and 0xff) shl 3)
                val packed = nullNibbleByte(0x303a14 + selected, t)
                var nibble = if (packIndex and 1 == 0) (packed and 0x0f) else (packed ushr 4)
                if (shift < 4) { val mask = if (shift == 0) 0 else (1 shl shift) - 1; nibble = nibble and mask }
                val byteIndex = packIndex shr 1
                if (packIndex and 1 == 0) packedLane[byteIndex] = nibble.toByte()
                else packedLane[byteIndex] = (packedLane[byteIndex].toInt() xor (nibble shl 4)).toByte()
                shift = maxOf(shift - 4, 0)
            }
            scheduleWords[outerIndex] = rdU32(packedLane, 0)
        }
        return Builder633fa8NullFirstLoopResult(finalTLane, scheduleWords)
    }

    private fun nullScheduleCheck(scheduleWords: UIntArray, sourceWords: UIntArray, scheduleMulTable: Int, sourceMulTable: Int, addTable: Int, foldTable: Int, target: UInt, foldTarget: UInt, t: Tables): Boolean {
        for (index in 19 downTo 0) {
            val to = (index * 4) and 0x1c
            val word = scheduleWords[index] * u32Tbl(scheduleMulTable + to, t) +
                sourceWords[index] * u32Tbl(sourceMulTable + to, t) +
                u32Tbl(addTable + to, t)
            if (word == target) continue
            val folded = fold633fa8NullCheck32(word, foldTable, 7, t)
            return (folded and 0x0fu) == foldTarget
        }
        return true
    }

    fun builder633fa8NullScheduleAcceptance(scheduleWords: UIntArray, check1SourceWords: UIntArray, check2SourceWords: UIntArray): Builder633fa8NullScheduleAcceptance {
        val t = Tables.shared
        val firstOK = nullScheduleCheck(scheduleWords, check1SourceWords, 0x11b228, 0x1152c8, 0x119688, 0x2fd338, 0xc5e51debu, 0x0bu, t)
        val secondOK = nullScheduleCheck(scheduleWords, check2SourceWords, 0x120e28, 0x11b248, 0x11f4e8, 0x2fd378, 0xfbc7d17cu, 0x0cu, t)
        return Builder633fa8NullScheduleAcceptance(firstOK, secondOK)
    }

    fun builder633fa8NullPostAcceptBlocks(scheduleWords: UIntArray): Builder633fa8NullPostAcceptResult {
        require(scheduleWords.size == 20)
        val t = Tables.shared
        val initCF0 = nullTableBlock(0x2fe5b8, 0x10, t)
        val initBD0 = nullTableBlock(0x2fe5ca, 0x10, t)
        val finalCF0Static = nullTableBlock(0x2fe5dc, 18, t)
        val finalBD0Static = nullTableBlock(0x2fe5ee, 18, t)
        val blocks4080 = ByteArray(20 * 0x10)
        val blocks3f40 = ByteArray(20 * 0x10)
        for (index in 0 until 20) {
            val to = (index * 4) and 0x1c
            val selector = scheduleWords[index] * u32Tbl(0x118de8 + to, t) + u32Tbl(0x113ee8 + to, t)
            var cf0State = initCF0
            var bd0State = initBD0
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val cf0Source = ByteArray(18); cf0Source[3] = 0x05
                System.arraycopy(cf0State, 0, cf0Source, 4, 8); System.arraycopy(cf0State, 8, cf0Source, 12, 4); System.arraycopy(cf0State, 12, cf0Source, 16, 2)
                val bd0Source = ByteArray(18); bd0Source[3] = 0x03
                System.arraycopy(bd0State, 0, bd0Source, 4, 8); System.arraycopy(bd0State, 8, bd0Source, 12, 4); System.arraycopy(bd0State, 12, bd0Source, 16, 2)
                val cf0 = vm638840(0x12000005508L, cf0Source, cf0Source, t)
                val bd0 = vm638840(0x12000005874L, bd0Source, bd0Source, t)
                val byteValue = ((selector shr shift) and 0xffu).toInt()
                val cf0Row = expand3BitPairTableRow633fa8Null(nullTableBlock(0x2fd3b8 + byteValue * 9, 9, t))
                val bd0Row = expand3BitPairTableRow633fa8Null(nullTableBlock(0x2fdcb8 + byteValue * 9, 9, t))
                cf0State = vm638840(0x12000002dc1L, cf0, cf0Row, t)
                bd0State = vm638840(0x1200000653bL, bd0, bd0Row, t)
            }
            val e10 = vm638840(0x120000019c2L, cf0State, finalCF0Static, t)
            val ab0 = vm638840(0x1200000552cL, bd0State, finalBD0Static, t)
            System.arraycopy(vm638840(0x10000005864L, e10, e10, t), 0, blocks4080, index * 0x10, 0x10)
            System.arraycopy(vm638840(0x10000006f0bL, ab0, ab0, t), 0, blocks3f40, index * 0x10, 0x10)
        }
        return Builder633fa8NullPostAcceptResult(blocks4080, blocks3f40)
    }

    private fun stitch633fa8NullPrelude11A(firstBlock: ByteArray, restBlocks: ByteArray): ByteArray {
        val out = ByteArray(0x11a)
        System.arraycopy(firstBlock, 0, out, 0, 0x10)
        for (index in 0 until 19) {
            val srcStart = index * 0x10 + 2
            val dstStart = 0x10 + index * 0x0e
            System.arraycopy(restBlocks, srcStart, out, dstStart, 0x0e)
        }
        return out
    }

    fun builder633fa8NullPreludeSourceFromPostAccept(blocks4080: ByteArray, blocks3f40: ByteArray): ByteArray {
        require(blocks4080.size == 20 * 0x10 && blocks3f40.size == 20 * 0x10)
        val t = Tables.shared
        val first4080 = vm638840(0x10000001638L, blocks4080.copyOfRange(0, 0x10), blocks4080.copyOfRange(0, 0x10), t)
        val rest4080 = ByteArray(19 * 0x10)
        for (index in 1 until 20) {
            val block = blocks4080.copyOfRange(index * 0x10, index * 0x10 + 0x10)
            System.arraycopy(vm638840(0x10000000d7cL, block, block, t), 0, rest4080, (index - 1) * 0x10, 0x10)
        }
        val bd0 = vm638840(0x11a000002ffdL, stitch633fa8NullPrelude11A(first4080, rest4080), stitch633fa8NullPrelude11A(first4080, rest4080), t)
        val first3f40 = vm638840(0x10000003690L, blocks3f40.copyOfRange(0, 0x10), blocks3f40.copyOfRange(0, 0x10), t)
        val rest3f40 = ByteArray(19 * 0x10)
        for (index in 1 until 20) {
            val block = blocks3f40.copyOfRange(index * 0x10, index * 0x10 + 0x10)
            System.arraycopy(vm638840(0x10000008167L, block, block, t), 0, rest3f40, (index - 1) * 0x10, 0x10)
        }
        val ab0 = vm638840(0x11a000003117L, stitch633fa8NullPrelude11A(first3f40, rest3f40), stitch633fa8NullPrelude11A(first3f40, rest3f40), t)
        val stage4080 = vm638840(0x11a00000267fL, bd0, ab0, t)
        val f40 = vm638840(0x11a000000ecfL, stage4080, ab0, t)
        return vm638840(0x10a000004e02L, f40, f40, t)
    }

    fun builder633fa8NullPreludeSourceFromEntropy(entropy11A: ByteArray): ByteArray {
        val sources = builder633fa8NullEntrySourcesFromInvariantEntry()
        val initial = builder633fa8NullInitialFromEntropy(entropy11A, sources.prologueSource)
        val loop = builder633fa8NullFirstLoopFromBlocks(initial.seedBlocks)
        val acceptance = builder633fa8NullScheduleAcceptance(loop.scheduleWords, sources.check1SourceWords, sources.check2SourceWords)
        if (!(acceptance.firstOK && acceptance.secondOK)) throw Rejected633fa8NullEntropy()
        val postAccept = builder633fa8NullPostAcceptBlocks(loop.scheduleWords)
        return builder633fa8NullPreludeSourceFromPostAccept(postAccept.blocks4080, postAccept.blocks3f40)
    }

    fun builder633fa8NullScalarWindowFromEntropy(entropy11A: ByteArray): ByteArray =
        builder633fa8ScalarWindowFromPreludeSource(builder633fa8NullPreludeSourceFromEntropy(entropy11A))

    fun builder633fa8NullScalarWindowFromEntropySource(maxAttempts: Int = 64, entropySource: (Int) -> ByteArray): Builder633fa8NullScalarResult {
        require(maxAttempts > 0)
        for (attempt in 1..maxAttempts) {
            val entropy = entropySource(0x11a)
            try {
                return Builder633fa8NullScalarResult(builder633fa8NullScalarWindowFromEntropy(entropy), entropy, attempt)
            } catch (e: Rejected633fa8NullEntropy) { continue }
        }
        throw FirstPairSliceException("633fa8 null entropy rejected after $maxAttempts attempts")
    }

    // ============================================================
    // 6421c0 caller engine + 5bcf98 P-256 outputs + HighSeed stream-start seeds
    // ============================================================

    class Builder5bcf98P256Outputs(val xOutput70: ByteArray, val yOutput70: ByteArray)
    class Builder6388f0HighSeedStreamStartSeeds(val out0: ByteArray, val out1: ByteArray)
    class Builder6388f0FirstPairHighSeedStreamStartSeeds(val row0: Builder6388f0HighSeedStreamStartSeeds, val row59: Builder6388f0HighSeedStreamStartSeeds)

    private val highSeed6421c0X2Source = byteArrayOf(
        0xd6.toByte(), 0xce.toByte(), 0x5d, 0x63, 0xde.toByte(), 0x75, 0xb3.toByte(), 0x91.toByte(),
        0x43, 0x98.toByte(), 0xc9.toByte(), 0xa1.toByte(), 0x23, 0x40, 0x76, 0x0f,
        0x3c, 0x69, 0x5a, 0x13, 0x9c.toByte(), 0xbb.toByte(), 0xc9.toByte(), 0x13,
        0x5d, 0x94.toByte(), 0xf6.toByte(), 0x57, 0xb7.toByte(), 0x29, 0x9c.toByte(), 0xb1.toByte(),
        0x82.toByte(), 0x46, 0x31, 0x56, 0x4e, 0x88.toByte(), 0x5b, 0x47,
        0x9d.toByte(), 0x21, 0x1c, 0xae.toByte(), 0xf3.toByte(), 0x69, 0xd9.toByte(), 0xea.toByte(),
        0x19, 0xae.toByte(), 0x4d, 0x0d, 0xc9.toByte(), 0x70, 0x20, 0x4b,
        0x5d, 0x94.toByte(), 0xf6.toByte(), 0x84.toByte(), 0xd7.toByte(), 0xde.toByte(), 0x58, 0xc2.toByte(),
        0x35, 0xac.toByte(), 0xa4.toByte(), 0x60, 0xfc.toByte(), 0x3d, 0xd5.toByte(), 0xb4.toByte(),
        0xc8.toByte(), 0x46, 0x76, 0x15, 0xc0.toByte(), 0xa7.toByte(), 0xe6.toByte(), 0xc0.toByte(),
        0x19, 0xae.toByte(), 0x4d, 0x0d, 0xc9.toByte(), 0x70, 0x20, 0x4b)
    private val highSeed6421c0X1Source = byteArrayOf(
        0xf9.toByte(), 0xb8.toByte(), 0xa2.toByte(), 0x3b, 0x79, 0x89.toByte(), 0x3d, 0xab.toByte(),
        0x28, 0xf6.toByte(), 0x8f.toByte(), 0x89.toByte(), 0x3a, 0x72, 0x9b.toByte(), 0xfc.toByte(),
        0x43, 0x32, 0x3b, 0x85.toByte(), 0x8f.toByte(), 0xcb.toByte(), 0xd6.toByte(), 0x95.toByte(),
        0xf4.toByte(), 0xd2.toByte(), 0x62, 0x09, 0x77, 0x91.toByte(), 0x59, 0xaf.toByte(),
        0xa1.toByte(), 0x03, 0xdf.toByte(), 0xee.toByte(), 0x09, 0x58, 0xb8.toByte(), 0x3b,
        0xb5.toByte(), 0x0a, 0x88.toByte(), 0x9d.toByte(), 0x20, 0x4a, 0xad.toByte(), 0xbb.toByte(),
        0xa4.toByte(), 0x80.toByte(), 0x61, 0x06, 0xa7.toByte(), 0x57, 0x0b, 0xca.toByte(),
        0xf4.toByte(), 0x52, 0x42, 0xee.toByte(), 0x5f, 0xa7.toByte(), 0xa2.toByte(), 0x7e,
        0xac.toByte(), 0x8b.toByte(), 0xc6.toByte(), 0xb0.toByte(), 0x87.toByte(), 0xa3.toByte(), 0x03, 0x84.toByte(),
        0xa3.toByte(), 0xc2.toByte(), 0xaf.toByte(), 0x99.toByte(), 0x20, 0x4a, 0xad.toByte(), 0xbb.toByte(),
        0xa4.toByte(), 0x80.toByte(), 0x61, 0x06, 0xa7.toByte(), 0x57, 0x0b, 0xca.toByte())
    private val highSeed6421c0Scalar = 0x68404ef676a9b7d3uL

    fun builder6421c0X0Streams(x0Source: ByteArray): Pair<ULongArray, ULongArray> {
        val t = Tables.shared
        val raw = ULongArray(20)
        for (index in 0 until 20) {
            val word = rdU32(x0Source, index * 4)
            val mixed = if (index == 0) word * 0x3239bd21u + 0x5c47f2f0u
            else u32Affine(word, index, 0x113f08, 0x113628, t) * 0x5da2e52fu + 0x6605175eu
            val folded = fold63c278(mixed.toULong() * 0x430e55e51aa99355uL + 0x15551dd776f38e14uL, 0x2feb18, 8, t)
            raw[index] = mixed.toULong() * 0xc788d39836400f55uL + folded * 0xd50b73ff00000000uL + 0xce6055b08c097bf0uL
        }
        return Pair(raw, prefixSumsU64(raw))
    }

    fun builder6421c0X1Streams(x1Source: ByteArray): Pair<ULongArray, ULongArray> {
        val t = Tables.shared
        val raw = ULongArray(V63)
        for (index in 0 until V63) {
            val word = rdU32(x1Source, index * 4)
            val mixed = if (index == 0) word * 0x105085d7u + 0x841874d8u
            else u32Affine(word, index, 0x11b288, 0x118388, t) * 0x97c9fb77u + 0x6b1b1a39u
            val folded = fold63c278(mixed.toULong() * 0x8f1272d1ced32651uL + 0x7eda487fd3a46989uL, 0x2feb98, 8, t)
            raw[index] = mixed.toULong() * 0x9dcd2446c70edca3uL + folded * 0xe9148d4d00000000uL + 0x2df1f5e9fb0ab4f8uL
        }
        return Pair(raw, prefixSumsU64(raw))
    }

    fun builder6421c0X2Words(x2Source: ByteArray): ULongArray {
        val t = Tables.shared
        val out = ULongArray(V63)
        for (index in 0 until V63) {
            val mixed = u32Affine(rdU32(x2Source, index * 4), index, 0x1183a8, 0x11b2a8, t) * 0x6819ef77u + 0x57cf46ceu
            val folded = fold63c278(mixed.toULong() * 0xc4e90084bd222fd1uL + 0xf9e4937efa15a0b7uL, 0x2fec18, 8, t)
            out[index] = mixed.toULong() * 0x06447e0a39c79467uL + folded * 0xcfaf794900000000uL + 0xe882bfc48de82700uL
        }
        return out
    }

    fun builder6421c0ConvolutionWorkspace(x0Raw: ULongArray, x0Prefix: ULongArray, x1Raw: ULongArray, x1Prefix: ULongArray): ByteArray {
        val words = ULongArray(44)
        for (index in 0 until 44) {
            val low = maxOf(0, index - (x1Raw.size - 1))
            val high = minOf(index, x0Raw.size - 1)
            var productSum = 0uL; var x0Sum = 0uL; var x1Sum = 0uL; var count = 0
            if (high >= low) {
                for (x0Index in low..high) productSum += x0Raw[x0Index] * x1Raw[index - x0Index]
                x0Sum = rangeSumFromPrefix(x0Prefix, low, high)
                x1Sum = rangeSumFromPrefix(x1Prefix, index - high, index - low)
                count = high - low + 1
            }
            val mixed = count.toULong() * 0xdd9e6926c32c9984uL + 0x7bf33cd7983bce3cuL +
                x0Sum * 0xe703af65ab19ca84uL + productSum * 0xe6337be2ad0561b9uL + x1Sum * 0x1cd6868a83aeef79uL
            words[index] = mixed * 0x2e60fd6d05fe470buL + 0xf48a714d4ddd3ee7uL
        }
        return packU64LE(words)
    }

    private fun builder6421c0WorkspaceParams(scalar: ULong, firstWord: ULong, t: Tables): Pair<ULong, ULong> {
        val seedA = scalar * 0x5509a203390f347fuL + 0x32f1fb0a9d874bf4uL
        val seedB = scalar * 0x4c2221c00f3005fbuL + 0x0ff14ba0b2a5c7bauL
        var mixed = firstWord * seedA + seedB
        val folded = fold63c278(mixed * 0x473c6a74e974ae65uL + 0xadebeda263d28433uL, 0x2fec98, 7, t)
        mixed = mixed * 0xef65aceeafea45e9uL + folded * 0x5fe62b0cb0000000uL + 0xd7d1a2ac976837c3uL
        return Pair(mixed * 0x9c52396943c088f7uL + 0x8983840ba934a2f1uL, mixed * 0x0fd36815b245b0f2uL + 0x3aa2f36c3a09d43euL)
    }

    private fun builder6421c0RewriteSecondWord(first: ULong, second: ULong, t: Tables): ULong {
        val folded = fold63c278(first * 0x12c340b4b411bb8duL + 0xab10f2a46110bcebuL, 0x2fed18, 7, t)
        var mixed = folded * 0xcfdc2f8d3b1f41e3uL + 0x317484327c6f968auL
        val folded2 = fold63c278(mixed * 0xeefa3d8f20f54f35uL + 0x483345b5f608f667uL, 0x2fed98, 9, t)
        mixed = mixed * 0x6b6283330fe2b923uL + folded2 * 0x6214609000000000uL
        return mixed * 0x8d48d385aeebeb5duL + second + 0x71783af05ec8119fuL
    }

    fun builder6421c0WorkspaceAfterUpdate(workspace: ByteArray, x2Words: ULongArray, scalar: ULong): ByteArray {
        val t = Tables.shared
        val words = ULongArray(44) { rdU64(workspace, it * 8) }
        for (base in 0 until V63) {
            val (mul, bcast) = builder6421c0WorkspaceParams(scalar, words[base], t)
            for (offset in 0 until V63) {
                val pos = base + offset
                words[pos] = words[pos] + bcast + x2Words[offset] * mul
            }
            words[base + 1] = builder6421c0RewriteSecondWord(words[base], words[base + 1], t)
        }
        return packU64LE(words)
    }

    fun builder6421c0FinalU32Words(workspace: ByteArray): UIntArray {
        val t = Tables.shared
        val words = ULongArray(44) { rdU64(workspace, it * 8) }
        var carry = 0x14ee1c03e369d629uL
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            val tailWord = words[V63 + index]
            val mixed = carry * 0x0338c0e89dc8ee71uL + tailWord * 0x32afeb8e00ff3e85uL + 0xfc9f014fa6b572f5uL
            val folded7 = fold63c278(mixed * 0xea4b89dcd43400c5uL + 0x3ea3d75ac0581688uL, 0x2fee18, 7, t)
            val side = ((mixed and 0xffffffffuL) * 0x279eaf81uL + (folded7 and 0xffffffffuL) * 0x30000000uL + 0xac5f152cuL).toUInt()
            val folded = fold63c278(folded7, 0x2fee18, 9, t)
            carry = folded7 * 0x571b49fe43ec4f5duL + folded * 0xc13b0a3000000000uL + 0x04e301c0d1003cfcuL
            val to = (index * 4) and 0x1c
            out[index] = side * u32Tbl(0x115e48 + to, t) + u32Tbl(0x115308 + to, t)
        }
        return out
    }

    fun builder6421c0OutputWords(x0Source: ByteArray, x1Source: ByteArray, x2Source: ByteArray, scalar: ULong): UIntArray {
        val (x0Raw, x0Prefix) = builder6421c0X0Streams(x0Source)
        val (x1Raw, x1Prefix) = builder6421c0X1Streams(x1Source)
        val x2Words = builder6421c0X2Words(x2Source)
        val workspace = builder6421c0ConvolutionWorkspace(x0Raw, x0Prefix, x1Raw, x1Prefix)
        val updated = builder6421c0WorkspaceAfterUpdate(workspace, x2Words, scalar)
        return builder6421c0FinalU32Words(updated)
    }

    fun builder5bcf98P256Outputs(scalarWindowLE: ByteArray, sensorPointXYBE: ByteArray): Builder5bcf98P256Outputs {
        require(scalarWindowLE.size >= 70 && sensorPointXYBE.size >= 64)
        val affine = P256ScalarMultiplier.AffinePoint(sensorPointXYBE.copyOfRange(0, 32), sensorPointXYBE.copyOfRange(32, 64))
        val product = P256ScalarMultiplier.multiply(scalarWindowLE.copyOf(70), affine)
        return Builder5bcf98P256Outputs(product.x.littleEndianPadded70, product.y.littleEndianPadded70)
    }

    fun builder6388f0HighSeedX0SourceFrom5bcf98Output(source70: ByteArray): ByteArray {
        require(source70.size >= 70)
        val t = Tables.shared
        val packedWords = UIntArray(18)
        for (index in 0 until 70) {
            val wordIndex = index / 4
            val shift = (index * 8) and 0x18
            packedWords[wordIndex] = packedWords[wordIndex] or ((source70[index].toInt() and 0xff).toUInt() shl shift)
        }
        val out = UIntArray(20)
        for (index in 0 until 20) {
            val bitOffset = index * 28
            val wordIndex = bitOffset ushr 5
            val shift = bitOffset and 0x1c
            var value = packedWords[wordIndex] shr shift
            if (shift != 0) value = value or (packedWords[wordIndex + 1] shl (32 - shift))
            value = value and 0x0fffffffu
            value = value * 0x83dcb233u + 0x774e86a1u
            out[index] = u32Affine(value, index, 0x11fd48, 0x1152e8, t)
        }
        return packU32LE(out)
    }

    fun builder6388f0HighSeedStreamStartSeedsFrom5bcf98Outputs(
        firstOutput70: ByteArray, secondOutput70: ByteArray, x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null,
    ): Builder6388f0HighSeedStreamStartSeeds {
        val rx1 = x1Source ?: highSeed6421c0X1Source
        val rx2 = x2Source ?: highSeed6421c0X2Source
        val rs = scalar ?: highSeed6421c0Scalar
        val out0 = builder6421c0OutputWords(builder6388f0HighSeedX0SourceFrom5bcf98Output(firstOutput70), rx1, rx2, rs)
        val out1 = builder6421c0OutputWords(builder6388f0HighSeedX0SourceFrom5bcf98Output(secondOutput70), rx1, rx2, rs)
        return Builder6388f0HighSeedStreamStartSeeds(packU32LE(out0), packU32LE(out1))
    }

    fun builder6388f0FirstPairHighSeedStreamStartSeedsFrom5bcf98Outputs(
        row0FirstOutput70: ByteArray, row0SecondOutput70: ByteArray, row59FirstOutput70: ByteArray, row59SecondOutput70: ByteArray,
        x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null,
    ): Builder6388f0FirstPairHighSeedStreamStartSeeds = Builder6388f0FirstPairHighSeedStreamStartSeeds(
        builder6388f0HighSeedStreamStartSeedsFrom5bcf98Outputs(row0FirstOutput70, row0SecondOutput70, x1Source, x2Source, scalar),
        builder6388f0HighSeedStreamStartSeedsFrom5bcf98Outputs(row59FirstOutput70, row59SecondOutput70, x1Source, x2Source, scalar))

    fun builder6388f0HighSeedStreamStartSeedsFromScalarP256(
        scalarWindowLE: ByteArray, sensorPointXYBE: ByteArray, x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null,
    ): Builder6388f0HighSeedStreamStartSeeds {
        val outputs = builder5bcf98P256Outputs(scalarWindowLE, sensorPointXYBE)
        return builder6388f0HighSeedStreamStartSeedsFrom5bcf98Outputs(outputs.xOutput70, outputs.yOutput70, x1Source, x2Source, scalar)
    }

    // ============================================================
    // Final assemblers: scalar windows + sensor P-256 points → stream seeds → source → key
    // ============================================================

    class Builder6388f0Row0LowSeedPreimages(val out4: ByteArray, val out3: ByteArray, val out2: ByteArray)

    fun builder6388f0Row0LowSeedPreimagesFromEntrySource(entrySource: ByteArray): Builder6388f0Row0LowSeedPreimages {
        val t = Tables.shared
        val seeds = builder6388f0LowSeedCF0SeedsFromEntrySource(entrySource)
        val phase3 = lowSeedPhaseFromCF0Seed(lowSeedPhase3Spec, seeds.phase3, t)
        val tailStatic = lowSeedStaticBlock(0x2f4f3c, t)
        val tailBD0 = vm638840(0x10a00000505eL, phase3, tailStatic, t)
        val tailS684 = vm638840(0x10a000004a08L, tailBD0, tailBD0, t)
        val pair = builder6388f0LowSeedTailPairFromEntrySource(entrySource)
        val tailStage = builder6388f0LowSeedTailStageFromPair(pair)
        val preludeSource = builder6388f0LowSeedPreludeSourceFromTailStage(tailStage)
        val seedBlocks = builder6388f0LowSeedBlocksFromPreludeSource(preludeSource)
        val loop = builder6388f0LowSeedLoopFromBlocks(seedBlocks)
        val baseOut3 = tailS684.copyOfRange(204, 266) + pair.right.copyOfRange(0, 26)
        val out3 = baseOut3.copyOfRange(0, 62) + loop.final6377f0 + baseOut3.copyOfRange(80, 88)
        return Builder6388f0Row0LowSeedPreimages(tailS684.copyOfRange(116, 204), out3, pair.right.copyOfRange(26, 114))
    }

    fun builder6388f0FirstPairStreamSeedsFrom5bcf98Outputs(
        row0Out4: ByteArray, row0Out3: ByteArray, row0Out2: ByteArray,
        row0FirstOutput70: ByteArray, row0SecondOutput70: ByteArray, row59FirstOutput70: ByteArray, row59SecondOutput70: ByteArray,
        nullScalarWindow: ByteArray = ByteArray(0), staticScalarWindow: ByteArray = ByteArray(0), nullEntropy11A: ByteArray = ByteArray(0),
        nullAttempts: Int = 0, x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null,
    ): Builder6388f0FirstPairStreamSeeds {
        val highSeeds = builder6388f0FirstPairHighSeedStreamStartSeedsFrom5bcf98Outputs(
            row0FirstOutput70, row0SecondOutput70, row59FirstOutput70, row59SecondOutput70, x1Source, x2Source, scalar)
        return Builder6388f0FirstPairStreamSeeds(
            nullScalarWindow, staticScalarWindow, nullEntropy11A, nullAttempts,
            row0Out4, row0Out3, row0Out2, highSeeds.row0.out1, highSeeds.row0.out0, highSeeds.row59.out1, highSeeds.row59.out0)
    }

    fun builder6388f0FirstPairStreamSeedsFromEntrySourceAnd5bcf98Outputs(
        entrySource: ByteArray, row0FirstOutput70: ByteArray, row0SecondOutput70: ByteArray, row59FirstOutput70: ByteArray, row59SecondOutput70: ByteArray,
        nullScalarWindow: ByteArray = ByteArray(0), staticScalarWindow: ByteArray = ByteArray(0), nullEntropy11A: ByteArray = ByteArray(0),
        nullAttempts: Int = 0, x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null,
    ): Builder6388f0FirstPairStreamSeeds {
        val low = builder6388f0Row0LowSeedPreimagesFromEntrySource(entrySource)
        val resolvedStatic = if (staticScalarWindow.isEmpty()) builder633fa8StaticScalarWindowFromEntrySource(entrySource) else staticScalarWindow
        return builder6388f0FirstPairStreamSeedsFrom5bcf98Outputs(
            low.out4, low.out3, low.out2, row0FirstOutput70, row0SecondOutput70, row59FirstOutput70, row59SecondOutput70,
            nullScalarWindow, resolvedStatic, nullEntropy11A, nullAttempts, x1Source, x2Source, scalar)
    }

    fun builder6388f0FirstPairStreamSeedsFromEntropyAnd5bcf98Outputs(
        entrySource: ByteArray, row0FirstOutput70: ByteArray, row0SecondOutput70: ByteArray, row59FirstOutput70: ByteArray, row59SecondOutput70: ByteArray,
        nullEntropy11A: ByteArray, x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null,
    ): Builder6388f0FirstPairStreamSeeds {
        val nullScalar = builder633fa8NullScalarWindowFromEntropy(nullEntropy11A)
        return builder6388f0FirstPairStreamSeedsFromEntrySourceAnd5bcf98Outputs(
            entrySource, row0FirstOutput70, row0SecondOutput70, row59FirstOutput70, row59SecondOutput70,
            nullScalar, ByteArray(0), nullEntropy11A, 1, x1Source, x2Source, scalar)
    }

    fun builder6388f0FirstPairStreamSeedsFromEntropySourceAnd5bcf98Outputs(
        entrySource: ByteArray, row0FirstOutput70: ByteArray, row0SecondOutput70: ByteArray, row59FirstOutput70: ByteArray, row59SecondOutput70: ByteArray,
        maxAttempts: Int = 64, x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null, entropySource: (Int) -> ByteArray,
    ): Builder6388f0FirstPairStreamSeeds {
        val nullResult = builder633fa8NullScalarWindowFromEntropySource(maxAttempts, entropySource)
        return builder6388f0FirstPairStreamSeedsFromEntrySourceAnd5bcf98Outputs(
            entrySource, row0FirstOutput70, row0SecondOutput70, row59FirstOutput70, row59SecondOutput70,
            nullResult.scalarWindow, ByteArray(0), nullResult.entropy11A, nullResult.attempts, x1Source, x2Source, scalar)
    }

    fun builder6388f0FirstPairStreamSeedsFromScalarsAndSensorPoints(
        entrySource: ByteArray, nullScalarWindow: ByteArray, staticScalarWindow: ByteArray, row0SensorPointXYBE: ByteArray, row59SensorPointXYBE: ByteArray,
        nullEntropy11A: ByteArray = ByteArray(0), nullAttempts: Int = 0, x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null,
    ): Builder6388f0FirstPairStreamSeeds {
        val low = builder6388f0Row0LowSeedPreimagesFromEntrySource(entrySource)
        val row0High = builder6388f0HighSeedStreamStartSeedsFromScalarP256(nullScalarWindow, row0SensorPointXYBE, x1Source, x2Source, scalar)
        val row59High = builder6388f0HighSeedStreamStartSeedsFromScalarP256(staticScalarWindow, row59SensorPointXYBE, x1Source, x2Source, scalar)
        return Builder6388f0FirstPairStreamSeeds(
            nullScalarWindow, staticScalarWindow, nullEntropy11A, nullAttempts,
            low.out4, low.out3, low.out2, row0High.out1, row0High.out0, row59High.out1, row59High.out0)
    }

    fun builder6388f0FirstPairStreamSeedsFromEntropyAndSensorPoints(
        entrySource: ByteArray, nullEntropy11A: ByteArray, row0SensorPointXYBE: ByteArray, row59SensorPointXYBE: ByteArray,
        x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null,
    ): Builder6388f0FirstPairStreamSeeds {
        val nullScalar = builder633fa8NullScalarWindowFromEntropy(nullEntropy11A)
        val staticScalar = builder633fa8StaticScalarWindowFromEntrySource(entrySource)
        return builder6388f0FirstPairStreamSeedsFromScalarsAndSensorPoints(
            entrySource, nullScalar, staticScalar, row0SensorPointXYBE, row59SensorPointXYBE, nullEntropy11A, 1, x1Source, x2Source, scalar)
    }

    fun builder6388f0FirstPairStreamSeedsFromEntropySourceAndSensorPoints(
        entrySource: ByteArray, row0SensorPointXYBE: ByteArray, row59SensorPointXYBE: ByteArray, maxAttempts: Int = 64,
        x1Source: ByteArray? = null, x2Source: ByteArray? = null, scalar: ULong? = null, entropySource: (Int) -> ByteArray,
    ): Builder6388f0FirstPairStreamSeeds {
        val nullResult = builder633fa8NullScalarWindowFromEntropySource(maxAttempts, entropySource)
        val staticScalar = builder633fa8StaticScalarWindowFromEntrySource(entrySource)
        return builder6388f0FirstPairStreamSeedsFromScalarsAndSensorPoints(
            entrySource, nullResult.scalarWindow, staticScalar, row0SensorPointXYBE, row59SensorPointXYBE, nullResult.entropy11A, nullResult.attempts, x1Source, x2Source, scalar)
    }

    fun deriveFrom6388f0FirstPairEntropyAndSensorPoints(
        entrySource: ByteArray, nullEntropy11A: ByteArray, row0SensorPointXYBE: ByteArray, row59SensorPointXYBE: ByteArray,
        src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10,
    ): ByteArray {
        val seeds = builder6388f0FirstPairStreamSeedsFromEntropyAndSensorPoints(entrySource, nullEntropy11A, row0SensorPointXYBE, row59SensorPointXYBE)
        return deriveFrom6388f0FirstPairStreamSeeds(seeds, src4, offset, length)
    }

    fun deriveFrom6388f0FirstPairEntropySourceAndSensorPoints(
        entrySource: ByteArray, row0SensorPointXYBE: ByteArray, row59SensorPointXYBE: ByteArray, maxAttempts: Int = 64,
        src4: ByteArray = byteArrayOf(0, 0, 0, 1), offset: Int = 0, length: Int = 0x10, entropySource: (Int) -> ByteArray,
    ): ByteArray {
        val seeds = builder6388f0FirstPairStreamSeedsFromEntropySourceAndSensorPoints(entrySource, row0SensorPointXYBE, row59SensorPointXYBE, maxAttempts, entropySource = entropySource)
        return deriveFrom6388f0FirstPairStreamSeeds(seeds, src4, offset, length)
    }

    fun phase5RawKeyFrom6388f0FirstPairEntropyAndSensorPoints(
        entrySource: ByteArray, nullEntropy11A: ByteArray, row0SensorPointXYBE: ByteArray, row59SensorPointXYBE: ByteArray, offset: Int = 0,
    ): ByteArray = Phase5KeySchedule.deriveRawKey(
        deriveFrom6388f0FirstPairEntropyAndSensorPoints(entrySource, nullEntropy11A, row0SensorPointXYBE, row59SensorPointXYBE, offset = offset, length = 0x10))

    // ============================================================
    // process2 phone-ephemeral PUBLIC key (entropy → scalar → 5bcf98 × fixed point → 0x04‖x‖y)
    // ============================================================

    private const val PROC2_BASE = 0x3038c0
    private fun process2TableBlock(libOffset: Int, byteCount: Int, t: Tables): ByteArray = checkedSlice(t.process2PublicTables, libOffset - PROC2_BASE, byteCount)
    private fun process2TableByte(libOffset: Int, t: Tables): Int = process2TableBlock(libOffset, 1, t)[0].toInt() and 0xff
    private fun foldProcess2(value: ULong, tableOffset: Int, rounds: Int, t: Tables): ULong {
        var folded = value
        repeat(rounds) { folded = rdU64(t.process2PublicTables, tableOffset - PROC2_BASE + (folded and 0xfuL).toInt() * 8) + (folded shr 4) }
        return folded
    }
    private fun cumulativeQwords(values: ULongArray): ULongArray { var total = 0uL; return ULongArray(values.size) { total += values[it]; total } }
    private fun diffCumulativeQwords(p: ULongArray): ULongArray { val out = ULongArray(p.size); if (p.isNotEmpty()) { out[0] = p[0]; for (i in 1 until p.size) out[i] = p[i] - p[i - 1] }; return out }
    private fun process2PrefixQword(word: UInt, foldTable: Int, qwordMul: ULong, qwordAdd: ULong, foldMul: ULong, finalMul: ULong, finalAdd: ULong, t: Tables): ULong {
        val folded = foldProcess2(word.toULong() * qwordMul + qwordAdd, foldTable, 8, t)
        return folded * foldMul + word.toULong() * finalMul + finalAdd
    }

    private val process2BSourceStaticWords = uintArrayOf(
        0xa99f067du, 0xb7043f80u, 0x2b6ee291u, 0xa4732ba2u, 0x6d3a9d91u, 0x4fd9d579u, 0x319597e5u, 0xfce96d28u, 0x48b26f75u, 0x05c01679u,
        0x5080bac6u, 0x2e25e6a6u, 0xbfbafcdfu, 0x8e127707u, 0x000d0fb3u, 0x4ac77820u, 0x7923dadfu, 0xe4ae8f3au, 0x5080bac6u, 0x2e25e6a6u)
    private val process2LowCopyOffsets = arrayOf(
        0x88 to 1, 0x90 to 0, 0x78 to 2, 0x68 to 4, 0x70 to 3, 0x58 to 6, 0x60 to 5, 0x48 to 8, 0x50 to 7, 0x38 to 10, 0x40 to 9, 0x28 to 12, 0x30 to 11)
    private val process2FixedPointBE = re.abbot.librecr.protocol.hexToBytes(
        "04a9bf2be2fd3d90f6467b8ca074710db3804eb0cfcc952a86d23289695d435ee0" +
        "9523a7d0e8aa2c53c6f7a49e9b6bd0db7a2d1035cd61876f37e43a74a1b65237")

    private fun process2ASourceWordsFromEntryArgSource(source11A: ByteArray): UIntArray {
        require(source11A.size == 0x11a)
        val t = Tables.shared
        val prelude = vm6420d8(0x810a000006dedL, source11A, source11A, t)
        val seedInputs = ByteArray(20 * 0x10)
        for (index in 0 until 19) System.arraycopy(prelude, index * 0x0e, seedInputs, index * 0x10, 0x10)
        System.arraycopy(process2TableBlock(0x3039e0, 0x10, t), 0, seedInputs, 19 * 0x10, 0x10)
        val seedBlocks = ByteArray(20 * 0x10)
        for (index in 0 until 20) {
            val block = seedInputs.copyOfRange(index * 0x10, index * 0x10 + 0x10)
            System.arraycopy(vm638840(0x10000000b46L, block, block, t), 0, seedBlocks, index * 0x10, 0x10)
        }
        val initCLane = process2TableBlock(0x3039f0, 0x10, t) + byteArrayOf(0x06, 0x06)
        val staticALane = process2TableBlock(0x303a02, 0x12, t)
        val out = UIntArray(20)
        for (outerIndex in 0 until 20) {
            val lane = outerIndex and 7
            val eSource = process2TableBlock(0x3038c0 + 0x12 * lane, 0x12, t)
            val dSource = process2TableBlock(0x303950 + 0x12 * lane, 0x12, t)
            val block = seedBlocks.copyOfRange(outerIndex * 0x10, outerIndex * 0x10 + 0x10)
            var bLane = vm638840(0x12000004162L, eSource, eSource, t)
            val dLaneInitial = vm6420d8(0x8010000000364L, block, block, t)
            var eLane = vm638840(0x120000047ceL, bLane, bLane, t)
            var cLane = initCLane
            repeat(28) {
                val fLane = vm638840(0x12000005eafL, dLaneInitial, cLane, t)
                val aLane = vm638840(0x12000003574L, staticALane, fLane, t)
                val tLane = vm638840(0x12000008187L, bLane, aLane, t)
                eLane = vm638840(0x1200000266dL, eLane, tLane, t)
                bLane = vm638840(0x12000000ebdL, bLane, bLane, t)
                cLane = vm638840(0x1200000504cL, cLane, cLane, t)
            }
            val fLane = vm638840(0x12000003be7L, eLane, bLane, t)
            val dLane = vm638840(0x12000000224L, fLane, dSource, t)
            var packELane = vm641fcc(0x0c00f000c00e96L, dLane, t)
            val packedLane = ByteArray(4)
            var shift = 32
            for (packIndex in 0 until 8) {
                val cWord = vm638840(0x4000004b12L, packELane, packELane, t)
                if (shift >= 5) packELane = vm6420d8(0x8010000805038L, packELane, packELane, t)
                val bWord = vm638840(0x40000019beL, cWord, cWord, t)
                val selected = (bWord[2].toInt() and 0xff) xor ((bWord[3].toInt() and 0xff) shl 3)
                val packed = process2TableByte(0x303a14 + selected, t)
                var nibble = if (packIndex and 1 == 0) (packed and 0x0f) else (packed ushr 4)
                if (shift < 4) { val mask = if (shift == 0) 0 else (1 shl shift) - 1; nibble = nibble and mask }
                val byteIndex = packIndex shr 1
                if (packIndex and 1 == 0) packedLane[byteIndex] = nibble.toByte()
                else packedLane[byteIndex] = (packedLane[byteIndex].toInt() xor (nibble shl 4)).toByte()
                shift = maxOf(shift - 4, 0)
            }
            out[outerIndex] = rdU32(packedLane, 0)
        }
        return out
    }

    private fun process2InitialPrefixes(aSourceWords: UIntArray, bSourceWords: UIntArray, t: Tables): Pair<ULongArray, ULongArray> {
        val aValues = ULongArray(20)
        var word = aSourceWords[0] * 0x68309fdfu + 0x9a8acd31u
        aValues[0] = process2PrefixQword(word, 0x303a58, 0xdea88f4cd7aa9967uL, 0x2498e0a8ace26d05uL, 0x2403505500000000uL, 0x77260d39cc35e0cduL, 0xf68d6a799b022952uL, t)
        for (index in 1 until 20) {
            val to = (index shl 2) and 0x1c
            word = aSourceWords[index] * u32Tbl(0x121988 + to, t) + u32Tbl(0x117328 + to, t)
            word = word * 0xa14d75f7u + 0x23fe38edu
            aValues[index] = process2PrefixQword(word, 0x303a58, 0xdea88f4cd7aa9967uL, 0x2498e0a8ace26d05uL, 0x2403505500000000uL, 0x77260d39cc35e0cduL, 0xf68d6a799b022952uL, t)
        }
        val bValues = ULongArray(20)
        word = bSourceWords[0] * 0xb417ac45u + 0xb9d0b931u
        bValues[0] = process2PrefixQword(word, 0x303ad8, 0xac344b5a12897c6duL, 0x7f6923d8cce61732uL, 0xcf8f92cb00000000uL, 0x671bb0c140212b91uL, 0x2ecd4bceff393710uL, t)
        for (index in 1 until 20) {
            val to = (index and 7) shl 2
            word = bSourceWords[index] * u32Tbl(0x1185e8 + to, t) + u32Tbl(0x112688 + to, t)
            word = word * 0x569e8293u + 0xa7b25d96u
            bValues[index] = process2PrefixQword(word, 0x303ad8, 0xac344b5a12897c6duL, 0x7f6923d8cce61732uL, 0xcf8f92cb00000000uL, 0x671bb0c140212b91uL, 0x2ecd4bceff393710uL, t)
        }
        return Pair(cumulativeQwords(aValues), cumulativeQwords(bValues))
    }

    private fun process2InitialWorkspaceFromPrefixes(aPrefix: ULongArray, bPrefix: ULongArray): ULongArray {
        val aVec = diffCumulativeQwords(aPrefix)
        val bVec = diffCumulativeQwords(bPrefix)
        val out = ULongArray(42)
        for (index in 0 until 42) {
            val low = maxOf(0, index - 19)
            val high = minOf(index, 19)
            var productSum = 0uL; var aSum = 0uL; var bSum = 0uL; var count = 0
            if (low <= high) {
                for (bIndex in low..high) productSum += aVec[index - bIndex] * bVec[bIndex]
                aSum = aPrefix[index - low]
                if (index - high - 1 >= 0) aSum -= aPrefix[index - high - 1]
                bSum = bPrefix[high]
                if (low != 0) bSum -= bPrefix[low - 1]
                count = high - low + 1
            }
            var value = count.toULong() * 0x94dfbb91a5378e68uL + 0x4218665245881823uL
            value += productSum * 0x501edede429b621fuL
            value += bSum * 0x6658ca76ca6e396auL
            value += aSum * 0x918160dbec5e059cuL
            value = value * 0xbcb96bc3c168e865uL + 0x242a710f34e73ceauL
            out[index] = value
        }
        return out
    }

    private fun process2Table35d8FromSourceWords(sourceWords: UIntArray, t: Tables): ULongArray {
        val out = ULongArray(20)
        for (index in 0 until 20) {
            val to = (index shl 2) and 0x1c
            var word = sourceWords[index] * u32Tbl(0x116988 + to, t) + u32Tbl(0x11d528 + to, t)
            word = word * 0x347334f7u + 0x7713d14du
            var qword = word.toULong() * 0x7378135b2404ba5fuL + 0x2bb7cb5d40ee4303uL
            qword = foldProcess2(qword, 0x303b58, 8, t)
            out[index] = word.toULong() * 0x714b9632f149a92duL + qword * 0x25f3200d00000000uL + 0x77db08099d019a2fuL
        }
        return out
    }

    private fun process2LowPrefixFromTable(qwords35d8: ULongArray, seed80: ULong): ByteArray {
        val out = ByteArray(0xb0)
        wrU64(out, 0xa8, seed80 * 0x93b6e33be4ad3c3fuL + 0x698d7878bd852e23uL)
        wrU64(out, 0x80, seed80 * 0x4a0e602e6ec97079uL + 0xad7d39c097694af0uL)
        for ((lowOffset, tableIndex) in process2LowCopyOffsets) wrU64(out, lowOffset, qwords35d8[tableIndex])
        return out
    }

    private fun process2QwordWorkspacesLast(lowPrefix: ByteArray, highStack: ByteArray, t: Tables): ULongArray {
        val low28 = rdU64(lowPrefix, 0x28); val low30 = rdU64(lowPrefix, 0x30); val low38 = rdU64(lowPrefix, 0x38)
        val low40 = rdU64(lowPrefix, 0x40); val low48 = rdU64(lowPrefix, 0x48); val low50 = rdU64(lowPrefix, 0x50)
        val low58 = rdU64(lowPrefix, 0x58); val low60 = rdU64(lowPrefix, 0x60); val low68 = rdU64(lowPrefix, 0x68)
        val low70 = rdU64(lowPrefix, 0x70); val low78 = rdU64(lowPrefix, 0x78); val low80 = rdU64(lowPrefix, 0x80)
        val low88 = rdU64(lowPrefix, 0x88); val low90 = rdU64(lowPrefix, 0x90); val lowA8 = rdU64(lowPrefix, 0xa8)
        val workspace = ULongArray(42) { rdU64(highStack, 0x360 + it * 8) }
        var x22 = workspace[0]
        val x6 = rdU64(highStack, 0x140); val x19 = rdU64(highStack, 0x148); val x21 = rdU64(highStack, 0x150)
        val x23 = rdU64(highStack, 0x158); val x24 = rdU64(highStack, 0x160); val x26 = rdU64(highStack, 0x168); val x28 = rdU64(highStack, 0x170)
        for (index in 0 until 22) {
            var state = x22 * lowA8 + low80
            var folded = foldProcess2(state * 0x87d6a191657cf88buL + 0x55ab3c8b3f81c5eauL, 0x303bd8, 7, t)
            state = state * 0x5513e20130c294ffuL + folded * 0x097f450230000000uL + 0x65416d6b1d6e1cbcuL
            val x27 = state * 0xde9a0217389253bbuL + 0x7368784697fb3dc5uL
            val x20 = state * 0x421be0fdc09a97cfuL + 0x492946def7da33b1uL
            var reg3 = x27 * low48 + x20
            val x22Head = x27 * low90 + x20 + x22
            val reg4 = x27 * low40 + x20
            folded = foldProcess2(x22Head * 0xe991db2a5d2a7faduL + 0xddaca38024dd36cduL, 0x303c58, 7, t)
            val x12 = folded * 0xcf053a359e1d9b81uL + 0xcfa9a29b5752d274uL
            folded = foldProcess2(x12 * 0x71795e15d000819buL + 0xf0c1332200ddc903uL, 0x303cd8, 9, t)
            val old = ULongArray(20) { workspace[index + it] }
            val outArr = ULongArray(20)
            outArr[0] = x22Head
            outArr[1] = x27 * low88 + x20 + old[1]
            outArr[2] = x27 * low78 + x20 + old[2]
            val acc13 = x27 * x6 + x20
            outArr[3] = x27 * low70 + x20 + old[3]
            outArr[4] = x27 * low68 + x20 + old[4]
            outArr[5] = x27 * low60 + x20 + old[5]
            outArr[6] = x27 * low58 + x20 + old[6]
            val acc15 = x27 * x19 + x20
            outArr[7] = x27 * low50 + x20 + old[7]
            outArr[8] = reg3 + old[8]
            val acc0 = x27 * x21 + x20
            outArr[9] = reg4 + old[9]
            val acc2 = x27 * low38 + x20
            outArr[10] = acc2 + old[10]
            val acc12 = x27 * low30 + x20
            outArr[11] = acc12 + old[11]
            val acc22 = x27 * low28 + x20
            outArr[12] = acc22 + old[12]
            val acc14 = x27 * x23 + x20
            val acc1 = x27 * x24 + x20
            outArr[13] = acc13 + old[13]
            outArr[14] = acc15 + old[14]
            val acc16 = x27 * x26 + x20
            val acc15Tail = x27 * x28 + x20
            outArr[15] = acc0 + old[15]
            outArr[16] = acc14 + old[16]
            var finalAcc = folded * 0x7a1cf7b000000000uL + x12 * 0x85a6c1a6777a6587uL
            finalAcc = finalAcc * 0xbf59bd30f12b2173uL + outArr[1]
            outArr[17] = acc1 + old[17]
            outArr[18] = acc16 + old[18]
            outArr[19] = acc15Tail + old[19]
            x22 = finalAcc + 0x0ba9328bc380f3f5uL
            outArr[1] = x22
            for (outIndex in 0 until 20) workspace[index + outIndex] = outArr[outIndex]
        }
        return workspace
    }

    private fun process2ScalarQwordsFromEntropy(entropy11A: ByteArray): ULongArray {
        val t = Tables.shared
        val x1Source = builder633fa8NullPublicEntrySourceFromEntropy(entropy11A)
        val aSourceWords = process2ASourceWordsFromEntryArgSource(x1Source)
        val (aPrefix, bPrefix) = process2InitialPrefixes(aSourceWords, process2BSourceStaticWords, t)
        val initialWorkspace = process2InitialWorkspaceFromPrefixes(aPrefix, bPrefix)
        val table35d8 = process2Table35d8FromSourceWords(invariantWords2dfc, t)
        val lowPrefix = process2LowPrefixFromTable(table35d8, 0xb6ccf02833a9825euL)
        val highStack = ByteArray(0x360 + 42 * 8)
        val repeated = table35d8[7]
        for (offset in intArrayOf(0x140, 0x148, 0x150, 0x158, 0x160, 0x168, 0x170)) wrU64(highStack, offset, repeated)
        for (i in 0 until 42) wrU64(highStack, 0x360 + i * 8, initialWorkspace[i])
        val workspace = process2QwordWorkspacesLast(lowPrefix, highStack, t)
        return ULongArray(20) { workspace[22 + it] }
    }

    private fun process2ScalarWordsFromQwords(qwords: ULongArray, t: Tables): UIntArray {
        var state = 0x8e047df005b7774buL
        val out = UIntArray(20)
        for (index in 0 until 20) {
            state = state * 0x4f9b1e335b5175b1uL + qwords[index] * 0xddc0126ec4f0da8buL + 0x807a205bcf09b957uL
            val foldedSeed = state * 0x0cc6d1cb7a71ea27uL + 0x75f17a53af690cbcuL
            val folded7 = foldProcess2(foldedSeed, 0x303d58, 7, t)
            var word = (state.toUInt().toULong() * 0xb904cc8buL + folded7.toUInt().toULong() * 0x30000000uL + 0x7733dbc5uL).toUInt()
            val folded16 = foldProcess2(folded7, 0x303d58, 9, t)
            val to = (index * 4) and 0x1c
            word = word * u32Tbl(0x1219a8 + to, t) + u32Tbl(0x1149e8 + to, t)
            out[index] = word
            state = folded7 * 0xf5b69300c49039c7uL + folded16 * 0xb6fc639000000000uL + 0x5c589cf77e794af2uL
        }
        return out
    }

    private fun process2ScalarWindowFromWords(words: UIntArray, t: Tables): ByteArray {
        val out = ByteArray(70)
        var acc = 0uL; var bits = 0; var outIndex = 0
        for (index in 0 until 20) {
            val to = (index * 4) and 0x1c
            var value = words[index] * u32Tbl(0x118608 + to, t) + u32Tbl(0x118f68 + to, t)
            value = value * 0x0b6afc2fu + 0x4608a396u
            acc = acc xor (value.toULong() shl bits)
            bits += 28
            while (bits > 16 && outIndex < 69) { out[outIndex] = (acc and 0xffuL).toByte(); acc = acc shr 8; outIndex += 1; bits -= 8 }
        }
        if (bits >= 1 && outIndex < 69) out[outIndex] = (acc and 0xffuL).toByte()
        return out
    }

    fun builder633fa8NullPublicEntrySourceFromEntropy(entropy11A: ByteArray): ByteArray {
        val preludeSource = builder633fa8NullPreludeSourceFromEntropy(entropy11A)
        val scalar = builder633fa8ScalarWindowFromPreludeSource(preludeSource)
        return preludeSource + scalar.copyOfRange(0, 0x10)
    }

    fun builderProcess2P5PublicScalarWindowFromEntropy(entropy11A: ByteArray): ByteArray {
        val t = Tables.shared
        val qwords = process2ScalarQwordsFromEntropy(entropy11A)
        val words = process2ScalarWordsFromQwords(qwords, t)
        return process2ScalarWindowFromWords(words, t)
    }

    fun builderProcess2P5PublicKey65FromEntropy(entropy11A: ByteArray): ByteArray {
        val scalarWindow = builderProcess2P5PublicScalarWindowFromEntropy(entropy11A)
        val fixedPoint = process2FixedPointBE.copyOfRange(1, process2FixedPointBE.size)
        val outputs = builder5bcf98P256Outputs(scalarWindow, fixedPoint)
        val xBE = outputs.xOutput70.copyOfRange(0, 32).reversedArray()
        val yBE = outputs.yOutput70.copyOfRange(0, 32).reversedArray()
        return byteArrayOf(0x04) + xBE + yBE
    }

    /** Convolution + carried-state reducer producing 44 u32 words from 22 a/b stream words. */
    private fun convolutionReducerU32Words63c278(
        a: ULongArray, b: ULongArray, stateInit: ULong, countMul: ULong, productMul: ULong, sumAMul: ULong, sumBMul: ULong,
        foldPreMul: ULong, foldPreAdd: ULong, foldTable: Int, sideMul: UInt, sideFoldedMul: UInt, sideAdd: UInt,
        nextFolded8Mul: ULong, nextFolded16Mul: ULong, nextAdd: ULong, outMulTable: Int, outAddTable: Int,
    ): UIntArray {
        val t = Tables.shared
        val aPrefix = prefixSumsU64(a)
        val bPrefix = prefixSumsU64(b)
        var state = stateInit
        val out = UIntArray(44)
        for (index in 0 until 44) {
            val start = maxOf(0, index - (V63 - 1))
            val end = minOf(index, V63 - 1)
            var productSum = 0uL
            if (start <= end) for (pos in start..end) productSum += a[pos] * b[index - pos]
            val span = if (start <= end) (end - start + 1).toULong() else 0uL
            val sumA = rangeSumFromPrefix(aPrefix, start, end)
            val sumB = rangeSumFromPrefix(bPrefix, index - end, index - start)
            val mixed = state + span * countMul + productSum * productMul + sumA * sumAMul + sumB * sumBMul
            val foldedSeed = mixed * foldPreMul + foldPreAdd
            val folded7 = fold63c278(foldedSeed, foldTable, 7, t)
            val folded16 = fold63c278(foldedSeed, foldTable, 16, t)
            val side = mixed.toUInt() * sideMul + folded7.toUInt() * sideFoldedMul + sideAdd
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(side, outMulTable + to, outAddTable + to, t)
            state = folded7 * nextFolded8Mul + folded16 * nextFolded16Mul + nextAdd
        }
        return out
    }

    // ---- 642f60 back half (Mid/Fourth/Fifth/Sixth/Seventh/Eighth + Outputs) ----

    class Builder642f60Result(val out0: ByteArray, val out1: ByteArray, val out2: ByteArray)
    class Strm(val a: ULongArray, val b: ULongArray, val c: ULongArray, val d: ULongArray)

    fun builder6388f0SharedContextFromBundle(): ByteArray = Tables.shared.sharedContext6388f0

    private val sourceBase6473d0Third: UIntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        u32WordsFromTableSegments(listOf(0x126340 to 16, 0x125500 to 16, 0x126340 to 16, 0x125500 to 16, 0x126340 to 16, 0x126af8 to 8), Tables.shared)
    }

    private val sourceBase6473d0Fifth: UIntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        u32WordsFromTableSegments(listOf(0x124e40 to 16, 0x125c20 to 16, 0x124e40 to 16, 0x125c20 to 16, 0x124e40 to 16, 0x126778 to 8), Tables.shared)
    }

    private val sourceBase6473d0NinthFirst: UIntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        u32WordsFromTableSegments(listOf(0x126550 to 16, 0x124f30 to 16, 0x126550 to 16, 0x124f30 to 16, 0x126550 to 16, 0x126c30 to 8), Tables.shared)
    }

    private val sourceBase6473d0NinthSecond: UIntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        u32WordsFromTableSegments(listOf(0x124f40 to 16, 0x126610 to 16, 0x124f40 to 16, 0x126610 to 16, 0x124f40 to 16, 0x126d10 to 8), Tables.shared)
    }

    private val sourceBase642f60Out0: UIntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        u32WordsFromTableSegments(listOf(0x125450 to 16, 0x126540 to 16, 0x125450 to 16, 0x126540 to 16, 0x125450 to 16, 0x126900 to 8), Tables.shared)
    }

    private val sourceBase642f60Seventh: UIntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        u32WordsFromTableSegments(listOf(0x125380 to 16, 0x1256c0 to 16, 0x125380 to 16, 0x1256c0 to 16, 0x125380 to 16, 0x126c70 to 8), Tables.shared)
    }

    private val defaultMidStageStaticSP9E0SP7D0: Strm by lazy(LazyThreadSafetyMode.PUBLICATION) {
        midStageStaticSP9E0SP7D0(0x9b3fe2a5f2a431c6uL)
    }

    fun builder642f60OutputsFromBundledContext(in0: ByteArray, in1: ByteArray, in2: ByteArray): Builder642f60Result =
        builder642f60Outputs(in0, in1, in2, builder6388f0SharedContextFromBundle())

    fun builder642f60Outputs(in0: ByteArray, in1: ByteArray, in2: ByteArray, contextSource: ByteArray): Builder642f60Result {
        val arg0 = contextSource.copyOfRange(0x100, 0x158)
        val scalar = rdU64(contextSource, 0x418)

        val sp2a8 = builder642f60StageSP2A8WordsFromX1(in1)
        val firstOutput = packU32LE(builder64bd0cOutputWords(arg0, scalar, builder642f60First64bd0cWorkspaceFromX1(in1, sp2a8)))
        val sp300 = builder642f60StageSP300WordsFrom64bd0cOutput(firstOutput)

        val sp1f8 = builder642f60StageSP1F8WordsFromX0(in0)
        val secondOutput = packU32LE(builder64bd0cOutputWords(arg0, scalar, builder642f60Second64bd0cWorkspace(sp1f8, sp300)))
        val sp250 = builder642f60StageSP250WordsFrom64bd0cOutput(secondOutput)

        val thirdOutput = packU32LE(builder64bd0cOutputWords(arg0, scalar, builder642f60Third64bd0cWorkspaceFromX2(in2)))
        val sp148 = builder642f60StageSP148WordsFrom64bd0cOutput(thirdOutput)

        val fourthOutput = packU32LE(builder64bd0cOutputWords(arg0, scalar, fourth64bd0cWorkspace(sp148)))
        val spf0 = builder642f60StageSPF0WordsFrom64bd0cOutput(fourthOutput)

        val midSP40 = midStageSP40WordsFromSPA90(midStageSPA90WordsFromX0(in0))
        val midStreams = midStageStreamsFromContextSPF0(contextSource, spf0)
        val midSP670 = midStageSP670Words(midStreams)
        val midSP40B = midStageSPA90SP880FromSP40(midSP40)
        val midStatic = defaultMidStageStaticSP9E0SP7D0
        // midSP40B.a = spa90Words (44), midSP40B.b = sp880Prefix = prefixSumsU64(spa90Words)
        val midSP510 = midStageSP510Words(midSP40B.a, midSP40B.b, midStatic.a, midStatic.b)

        val fifthOutput = packU32LE(builder64bd0cOutputWords(arg0, scalar, midFifth64bd0cWorkspace(midSP670, midSP510)))
        val sp1a0 = builder642f60StageSP1A0WordsFrom64bd0cOutput(fifthOutput)

        val sixthStreams = sixthStreamsFromSP1A0(sp1a0)
        val sixthOutput = packU32LE(builder64bd0cOutputWords(arg0, scalar, sixth64bd0cWorkspace(sixthStreams)))

        val out0Source = out0SourceWords(sixthOutput, contextSource, sp250)
        val out0 = out0WordsFromSource(out0Source)

        val seventhSource = seventhSourceWords(sp250, contextSource, out0)
        val seventhSP148 = seventhStageSP148WordsFromSource(seventhSource)
        val seventhStreams = seventhStreams(sp1a0, seventhSP148)
        val seventhSP9E0 = seventhSP9E0Words(seventhStreams)
        val seventhSPA90 = seventhSPA90WordsFromSP300(sp300)
        val seventhSP7D0 = seventhSP7D0WordsFromSPA90(seventhSPA90)
        val seventhSource44 = seventhSource44Words(seventhSP9E0, contextSource, seventhSP7D0)
        val seventhSP40 = seventhSP40WordsFromSource44(seventhSource44)
        val seventhOutput = packU32LE(builder64bd0cOutputWords(arg0, scalar, seventh64bd0cWorkspace(seventhSP40)))
        val out1 = affineWordsFrom64bd0cOutput(seventhOutput, 0x11ebc8, 0x11e4e8)

        val eighthStreams = eighthStreams(sp2a8, in2)
        val eighthOutput = packU32LE(builder64bd0cOutputWords(arg0, scalar, eighth64bd0cWorkspace(eighthStreams)))
        val out2 = affineWordsFrom64bd0cOutput(eighthOutput, 0x119f48, 0x119f68)

        return Builder642f60Result(packU32LE(out0), packU32LE(out1), packU32LE(out2))
    }

    private fun fourth64bd0cWorkspace(sp148: UIntArray): ByteArray {
        val t = Tables.shared
        val a = ULongArray(V63) { fourthAWord(sp148[it], it, t) }
        val b = ULongArray(V63) { fourthBWord(sp148[it], it, t) }
        return convolutionWorkspaceU64(a, b, 0xca87452057c62cf5uL, 0x33f7ea217636a2b0uL, 0x25c9902b9655a323uL, 0x5486edf9ebf09668uL, 0x9ae2908cd350c4cauL, 0xc84690dc7332d8bfuL, 0x2381c41e82ce093duL)
    }

    private fun midStageSPA90WordsFromX0(x0: ByteArray): ULongArray {
        val t = Tables.shared
        return ULongArray(V63) {
            val affine = u32Affine(rdU32(x0, it * 4), it, 0x114828, 0x118e48, t)
            val word = affine * 0x3c1bc237u + 0xd6718b75u
            val folded = fold63c278(word.toULong() * 0x4570116131d5875buL + 0x4ca880cd5cde550euL, 0x2ff5f0, 8, t)
            word.toULong() * 0xc7860ccbc266aa3duL + folded * 0xbffb9fb900000000uL + 0x0bfdc66a47f4cadfuL
        }
    }

    private fun midStageSP40WordsFromSPA90(spa90: ULongArray): UIntArray {
        val t = Tables.shared
        var carry = 0xd2263697af87081fuL
        val out = UIntArray(44)
        for (index in 0 until 44) {
            var low = maxOf(0, index - 21); var high = minOf(index, 21)
            var accum = 0x4ca9f4732c4678dauL
            while (high > low) {
                val highWord = spa90[high]; val lowWord = spa90[low]
                val left = highWord * 0xb2358691225cfc35uL + 0xdf9a7386fc929cb6uL
                accum = left * lowWord + highWord * 0xdf9a7386fc929cb6uL + accum + 0xe1240ffc79c75054uL
                high -= 1; low += 1
            }
            val mixed = accum * 0x7047539999fd499euL
            carry *= 0x7d2900791bc15f17uL
            val state = if (high == low) {
                val center = spa90[high]
                val centerTerm = center * 0xe6b5f1d6d357e2dbuL + 0x7888b2a9570a9e54uL
                centerTerm * center + mixed + carry + 0xe771da0c03bd224cuL
            } else mixed + carry + 0xb928102d38c55e60uL
            val folded7 = fold63c278(state * 0x93dfdd33afa41fcbuL + 0xb5028820475851e2uL, 0x2ff670, 7, t)
            val folded16 = fold63c278(folded7, 0x2ff670, 9, t)
            val side = state.toUInt() * 0x6d4b301fu + folded7.toUInt() * 0x30000000u + 0xb22b53c3u
            carry = folded7 * 0x33b21893aa33e715uL + folded16 * 0x5cc18eb000000000uL + 0xd1af5299bbb3ce82uL
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(side, 0x116848 + to, 0x123ce8 + to, t)
        }
        return out
    }

    private fun midStageStreamsFromContextSPF0(contextSource: ByteArray, spf0: UIntArray): Strm {
        val t = Tables.shared
        val ctxWords = UIntArray(V63) { rdU32(contextSource, 0xa8 + it * 4) }
        val spa90 = ULongArray(V63) { midContextStreamWord(ctxWords[it], it, t) }
        val sp880 = ULongArray(V63) { midSPF0StreamWord(spf0[it], it, t) }
        return Strm(spa90, prefixSumsU64(spa90), sp880, prefixSumsU64(sp880))
    }

    private fun midStageSP670Words(s: Strm): ULongArray {
        // s.a=spa90, s.b=sp510Prefix, s.c=sp880, s.d=sp9e0Prefix
        val out = ULongArray(44)
        for (index in 0 until 44) {
            val start = maxOf(0, index - 21); val end = minOf(index, 21)
            var productSum = 0uL
            if (start <= end) for (p in start..end) productSum += s.a[p] * s.c[index - p]
            val span = (if (start <= end) end - start + 1 else 0).toULong()
            val sumA = rangeSumFromPrefix(s.b, start, end)
            val sumB = rangeSumFromPrefix(s.d, index - end, index - start)
            val mixed = span * 0x268d985caf171be0uL + 0x91891dd268ac7a45uL + productSum * 0xbc643695604233c9uL + sumA * 0x55d047a51fd1fdd0uL + sumB * 0x268318c9a7c7fd06uL
            out[index] = mixed * 0xc36e55bcdc7360d9uL + 0x20aeeecb67e4d8eeuL
        }
        return out
    }

    private fun midStageSPA90SP880FromSP40(sp40: UIntArray): Strm {
        val t = Tables.shared
        val spa90 = ULongArray(44) { midSP40BWord(sp40[it], it, t) }
        return Strm(spa90, prefixSumsU64(spa90), ULongArray(0), ULongArray(0))
    }

    private fun midStageStaticSP9E0SP7D0(sideInit: ULong): Strm {
        val t = Tables.shared
        val sp9e0 = ULongArray(V63)
        sp9e0[0] = sideInit
        for (index in 1 until V63) {
            val to = (index * 4) and 0x1c
            val source = foldTbl(0x2fee98 + index * 4, t)
            val multiplier = u32Tbl(0x11b388 + to, t)
            val addend = u32Tbl(0x1233e8 + to, t)
            val word = (source * multiplier + addend) * 0x8e3923f3u + 0xdcf87258u
            val folded = fold63c278(word.toULong() * 0x76e0c10d644166b9uL + 0x9f48a8b2fd92040duL, 0x2ff870, 8, t)
            sp9e0[index] = word.toULong() * 0x5a02cb2433277ab9uL + folded * 0xedf34bff00000000uL + 0x6f59c0117d1d1775uL
        }
        return Strm(sp9e0, prefixSumsU64(sp9e0), ULongArray(0), ULongArray(0))
    }

    private fun midStageSP510Words(spa90: ULongArray, sp880Prefix: ULongArray, sp9e0: ULongArray, sp7d0Prefix: ULongArray): ULongArray {
        val out = ULongArray(44)
        for (index in 0 until 44) {
            val start = maxOf(0, index - 21); val end = index
            var productSum = 0uL
            for (p in start..end) productSum += spa90[p] * sp9e0[index - p]
            val span = end - start + 1
            val sumA = rangeSumFromPrefix(sp880Prefix, start, end)
            val sumB = sp7d0Prefix[span - 1]
            val mixed = productSum * 0x29f4a886cd96e34duL + span.toULong() * 0xfb27869a34fe306euL + sumA * 0x7228cc7a696bf425uL + sumB * 0x4005e2eb6883e7deuL
            out[index] = mixed * 0x10af80ba2ba8ff03uL + 0x603f2c10b20e1521uL
        }
        return out
    }

    private fun midFifth64bd0cWorkspace(sp670: ULongArray, sp510: ULongArray): ByteArray {
        val out = ULongArray(44) { sp670[it] * 0x311e50313531405duL + sp510[it] * 0xc817dbca0a20eafduL + 0xc254ca1fa792908cuL }
        return packU64LE(out)
    }

    private fun sixthStreamsFromSP1A0(sp1a0: UIntArray): Strm {
        val t = Tables.shared
        val spa90 = ULongArray(V63) { sixthAWord(sp1a0[it], it, t) }
        val sp880 = ULongArray(V63) { sixthBWord(sp1a0[it], it, t) }
        return Strm(spa90, prefixSumsU64(spa90), sp880, prefixSumsU64(sp880))
    }

    private fun sixth64bd0cWorkspace(s: Strm): ByteArray {
        val out = ULongArray(44)
        for (index in 0 until 44) {
            val start = maxOf(0, index - 21); val end = minOf(index, 21)
            var productSum = 0uL
            if (start <= end) for (p in start..end) productSum += s.a[p] * s.c[index - p]
            val span = (if (start <= end) end - start + 1 else 0).toULong()
            val sumA = rangeSumFromPrefix(s.b, start, end)
            val sumB = rangeSumFromPrefix(s.d, index - end, index - start)
            val mixed = span * 0xc9579b83c731c3c0uL + 0x5c81c51b07a75dd5uL + productSum * 0x5af5ce9c3c24da93uL + sumA * 0x22758d71fea188c0uL + sumB * 0xf1d0ed7a635c3b3fuL
            out[index] = mixed * 0xa731aa4721be8565uL + 0x25f1b6bafa949dffuL
        }
        return packU64LE(out)
    }

    private fun out0SourceWords(sixthOutput: ByteArray, contextSource: ByteArray, sp250: UIntArray): UIntArray {
        val t = Tables.shared
        val base = sourceBase642f60Out0
        return UIntArray(V63) { index ->
            val to = (index * 4) and 0x1c
            val sixthWord = rdU32(sixthOutput, index * 4)
            val contextWord = rdU32(contextSource, 0x1b0 + index * 4)
            var word = base[index] + sixthWord * u32Tbl(0x1229e8 + to, t)
            word += contextWord * u32Tbl(0x123d28 + to, t)
            val sp250Delta = sp250[index] * u32Tbl(0x1183e8 + to, t)
            word + sp250Delta + sp250Delta
        }
    }

    private fun out0WordsFromSource(source: UIntArray): UIntArray =
        affineFoldStage(source, 0xb326b224u, 0x3d98bc67u, 0xe98a6e39u, 0xa9ce435cu, 0x2ff9f0, 0x88625dcfu, 0x90000000u, 0x647eea94u, 0x50717a0fu, 0xf8e85f10u, 0x119b9786u, 0x11d428, 0x120e88, true)

    private fun seventhSourceWords(sp250: UIntArray, contextSource: ByteArray, out0: UIntArray): UIntArray {
        val t = Tables.shared
        val base = sourceBase642f60Seventh
        return UIntArray(V63) { index ->
            val to = (index * 4) and 0x1c
            val contextWord = rdU32(contextSource, 0x208 + index * 4)
            base[index] + sp250[index] * u32Tbl(0x11d448 + to, t) + contextWord * u32Tbl(0x117ae8 + to, t) + out0[index] * u32Tbl(0x11cd68 + to, t)
        }
    }

    private fun seventhStageSP148WordsFromSource(source: UIntArray): UIntArray =
        affineFoldStage(source, 0xf92a7de1u, 0x2bd72421u, 0x79766d05u, 0x22dc5eefu, 0x2ffa30, 0x072b272du, 0x70000000u, 0x63742f4bu, 0x04d53e2du, 0xb2ac1d30u, 0x1cf006ebu, 0x1153c8, 0x11dc68, true)

    private fun seventhStreams(sp1a0: UIntArray, sp148: UIntArray): Strm {
        val t = Tables.shared
        val sp670 = ULongArray(V63) { u64StreamWord(sp1a0[it], it, 0x112d88, 0x1153e8, 0xf36a661du, 0x55308919u, 0xce5ac3ad5b5dac97uL, 0x48dc073b21398a79uL, 0x2ffa70, 0xfa62b370c3eadc41uL, 0xf45f1f1900000000uL, 0x6fc778fe52193dd5uL, t) }
        val sp510 = ULongArray(V63) { u64StreamWord(sp148[it], it, 0x11a8e8, 0x1136a8, 0x84c35e4fu, 0xea2fdd20u, 0x1f41e4ec093ed9f7uL, 0x27d1733855de4d16uL, 0x2ffaf0, 0x9fac6b22392e3497uL, 0x09482d9f00000000uL, 0xf6042c7612dc729euL, t) }
        return Strm(sp670, shiftedPrefixSumsU64(sp670), sp510, shiftedPrefixSumsU64(sp510))
    }

    private fun seventhSP9E0Words(s: Strm): UIntArray {
        // s.a=sp670, s.b=spa90Prefix(23), s.c=sp510, s.d=sp880Prefix(23)
        val t = Tables.shared
        var state = 0x8360a2c993f75737uL
        val out = UIntArray(44)
        for (index in 0 until 44) {
            val start = maxOf(0, index - (V63 - 1)); val end = minOf(index, V63 - 1)
            val mixed: ULong
            if (start <= end) {
                var productSum = 0uL
                for (p in start..end) productSum += s.a[p] * s.c[index - p]
                val span = (end - start + 1).toULong()
                val sumA = s.b[end + 1] - s.b[start]
                val sumB = s.d[index - start + 1] - s.d[index - end]
                mixed = state + span * 0x005dbd39bbb74611uL + productSum * 0x376b7bf8523b310fuL + sumA * 0x05844a4f0ab6c52buL + sumB * 0xbcb254e552fa427duL
            } else mixed = state
            val folded7 = fold63c278(mixed * 0x8db6469e177ed14buL + 0x0980afdda9144775uL, 0x2ffb70, 7, t)
            val folded8 = fold63c278(folded7, 0x2ffb70, 1, t)
            val folded16 = fold63c278(folded8, 0x2ffb70, 8, t)
            val side = mixed.toUInt() * 0xe15d12adu + folded7.toUInt() * 0x90000000u + 0x07600fb6u
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(side, 0x123d48 + to, 0x123d68 + to, t)
            state = folded7 * 0xfac2e2a1bcc53063uL + folded16 * 0x33acf9d000000000uL + 0x42e2c949e6b96dc1uL
        }
        return out
    }

    private fun seventhSPA90WordsFromSP300(sp300: UIntArray): ULongArray {
        val t = Tables.shared
        return ULongArray(V63) { u64StreamWord(sp300[it], it, 0x11eba8, 0x11e4c8, 0x923b2603u, 0x0d7c3c6du, 0x461236e7241ea4afuL, 0xc0bb06ebd489d8f1uL, 0x2ffbf0, 0x1925dd7dc803ae75uL, 0x6a8f4fe500000000uL, 0x52f0304276b65fdeuL, t) }
    }

    private fun seventhSP7D0WordsFromSPA90(spa90: ULongArray): UIntArray {
        val t = Tables.shared
        var state = 0xd71b81e668a07680uL
        val out = UIntArray(44)
        for (index in 0 until 44) {
            val start = maxOf(0, index - (V63 - 1)); val end = minOf(index, V63 - 1)
            var pairAccumulator = 0xb616243568409e12uL
            var left = start; var right = end
            if (end > start) {
                while (true) {
                    val endWord = spa90[right]; right -= 1
                    val product = endWord * 0x3a825182ec92a9efuL + 0x975bbf5d33a0b7f4uL
                    var mixedPair = endWord * 0x975bbf5d33a0b7f4uL + pairAccumulator
                    val startWord = spa90[left]; left += 1
                    mixedPair = product * startWord + mixedPair
                    pairAccumulator = mixedPair + 0x4657dd9b924a1870uL
                    if (right <= left) break
                }
            }
            pairAccumulator *= 0xc3c1f54f3c2cd4a6uL
            val scaledState = state * 0x7f0a8f747ca98163uL
            val mixed = if (right == left) {
                val center = spa90[right]
                val centerMixed = center * 0x8b03bdcc8a740e7duL + 0x25af1839607d5838uL
                centerMixed * center + pairAccumulator + scaledState + 0xe804eb7226c5f391uL
            } else pairAccumulator + scaledState + 0x0bea08ebd101a741uL
            val product = mixed * 0x416e14010d9d6b21uL
            val firstFold = foldTbl64(0x2ffc70 + (product and 0x0fuL).toInt() * 8, t) + ((product + 0xe4602986bf1f9a80uL) shr 4)
            val folded7 = fold63c278(firstFold, 0x2ffc70, 6, t)
            val folded8 = fold63c278(folded7, 0x2ffc70, 1, t)
            val side = mixed.toUInt() * 0x3e3dcae5u + folded7.toUInt() * 0xb0000000u + 0x8a63e3dcu
            val folded16 = fold63c278(folded8, 0x2ffc70, 8, t)
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(side, 0x114868 + to, 0x122a08 + to, t)
            state = folded7 * 0x38c35e2d317591ebuL + folded16 * 0xe8a6e15000000000uL + 0x7cfc7c8b77dde511uL
        }
        return out
    }

    private fun seventhSource44Words(sp9e0: UIntArray, contextSource: ByteArray, sp7d0: UIntArray): UIntArray {
        val t = Tables.shared
        return UIntArray(44) { index ->
            val to = (index * 4) and 0x1c
            val contextWord = rdU32(contextSource, 0x368 + index * 4)
            val sp7d0Delta = sp7d0[index] * u32Tbl(0x123408 + to, t)
            u32Tbl(0x118408 + to, t) + sp9e0[index] * u32Tbl(0x11f528 + to, t) + contextWord * u32Tbl(0x120ea8 + to, t) + sp7d0Delta + sp7d0Delta
        }
    }

    private fun seventhSP40WordsFromSource44(source: UIntArray): UIntArray {
        val t = Tables.shared
        var state = 0xcfda05bau
        val out = UIntArray(44)
        for (index in 0 until 44) {
            val to = (index * 4) and 0x1c
            state = state * 0x0862c569u + source[index]
            var folded7 = state * 0x5e8a87f3u + 0x54d7c56fu
            folded7 = fold32ByNibbles63c278(folded7, 0x2ffcf0, 7, t)
            val side = state * 0x12f83eedu + (folded7 shl 28) + 0x51f93a0au
            val folded8 = foldTbl(0x2ffcf0 + (folded7 and 0x0fu).toInt() * 4, t) + (folded7 shr 4)
            out[index] = u32TblAffine(side, 0x11bb68 + to, 0x1196a8 + to, t)
            state = folded7 * 0x36a73103u + folded8 * 0x958cefd0u + 0x9e56fff6u
        }
        return out
    }

    private fun seventh64bd0cWorkspace(sp40: UIntArray): ByteArray {
        val t = Tables.shared
        val out = ULongArray(44) {
            u64StreamWord(sp40[it], it, 0x123d88, 0x113f88, 0x2e6bbea3u, 0xe3db739au, 0x40c95ec2845e4b0buL, 0xb5edeaa67030b38duL, 0x2ffd30, 0xa2d77df3e3f51135uL, 0x7122434100000000uL, 0xb3aefd596d371f14uL, t)
        }
        return packU64LE(out)
    }

    private fun eighthStreams(sp2a8: UIntArray, x2Source: ByteArray): Strm {
        val t = Tables.shared
        val x2Words = UIntArray(V63) { rdU32(x2Source, it * 4) }
        val spa90 = ULongArray(V63) { eighthAWord(sp2a8[it], it, t) }
        val sp880 = ULongArray(V63) { eighthBWord(x2Words[it], it, t) }
        return Strm(spa90, prefixSumsU64(spa90), sp880, prefixSumsU64(sp880))
    }

    private fun eighth64bd0cWorkspace(s: Strm): ByteArray {
        val out = ULongArray(44)
        for (index in 0 until 44) {
            val start = maxOf(0, index - (V63 - 1)); val end = minOf(index, V63 - 1)
            val mixed: ULong
            if (start <= end) {
                var productSum = 0uL
                for (p in start..end) productSum += s.a[p] * s.c[index - p]
                val span = (end - start + 1).toULong()
                val sumA = rangeSumFromPrefix(s.b, start, end)
                val sumB = rangeSumFromPrefix(s.d, index - end, index - start)
                mixed = span * 0x05f89c998f88e9a2uL + 0xe3449c12b03ff8d9uL + productSum * 0xeab93afc6984b71duL + sumA * 0xc2592d51a5992a23uL + sumB * 0xf8e4c71d4c7a89deuL
            } else mixed = 0xe3449c12b03ff8d9uL
            out[index] = mixed * 0xca274927c26656e9uL + 0x89706c698c29e887uL
        }
        return packU64LE(out)
    }

    // shared affine+fold stage (out0/sp148 family)
    private fun affineFoldStage(
        source: UIntArray, init: UInt, stateMul: UInt, foldMul: UInt, foldAdd: UInt, foldTable: Int,
        sideMul: UInt, sideFoldMul: UInt, sideAdd: UInt, nextFold7Mul: UInt, nextFold8Mul: UInt, nextAdd: UInt,
        outMulTable: Int, outAddTable: Int, sideShift: Boolean,
    ): UIntArray {
        val t = Tables.shared
        var state = init
        val out = UIntArray(source.size)
        for (index in source.indices) {
            val to = (index * 4) and 0x1c
            state = state * stateMul + source[index]
            var folded7 = state * foldMul + foldAdd
            folded7 = fold32ByNibbles63c278(folded7, foldTable, 7, t)
            val side = state * sideMul + folded7 * sideFoldMul + sideAdd
            val folded8 = fold32ByNibbles63c278(folded7, foldTable, 1, t)
            out[index] = u32TblAffine(side, outMulTable + to, outAddTable + to, t)
            state = folded7 * nextFold7Mul + folded8 * nextFold8Mul + nextAdd
        }
        return out
    }

    private fun u32WordsFromTableSegments(segments: List<Pair<Int, Int>>, t: Tables): UIntArray {
        val out = ArrayList<UInt>()
        for ((offset, byteCount) in segments) { var o = 0; while (o < byteCount) { out.add(u32Tbl(offset + o, t)); o += 4 } }
        return out.toUIntArray()
    }

    private fun u64StreamWord(word: UInt, index: Int, mulTable: Int, addTable: Int, u32Mul: UInt, u32Add: UInt, foldMul: ULong, foldAdd: ULong, foldTable: Int, linearMul: ULong, foldedMul: ULong, linearAdd: ULong, t: Tables): ULong {
        val affine = u32Affine(word, index, mulTable, addTable, t)
        val w = affine * u32Mul + u32Add
        val folded = fold63c278(w.toULong() * foldMul + foldAdd, foldTable, 8, t)
        return w.toULong() * linearMul + folded * foldedMul + linearAdd
    }

    private fun fourthAWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x9a4392dbu + 0x0d1015eau else u32Affine(word, index, 0x112588, 0x123cc8, t) * 0x97151be3u + 0x70bf5e2bu
        val folded = fold63c278(w.toULong() * 0xcf0e32fa8d969f65uL + 0x61afec1284e66a8cuL, 0x2ff4f0, 8, t)
        return w.toULong() * 0x610ca66bd199f2b5uL + folded * 0xde6166ef00000000uL + 0x50d31e15d8b1af56uL
    }

    private fun fourthBWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x2ac5e1c1u + 0x957be66cu else u32Affine(word, index, 0x115e68, 0x1125a8, t) * 0xee64b1f5u + 0x5df44367u
        val folded = fold63c278(w.toULong() * 0x013aed389aef9cd9uL + 0x9ac1ba0fa43555a1uL, 0x2ff570, 8, t)
        return w.toULong() * 0x3ca485be7caa6cf3uL + folded * 0xcec7175500000000uL + 0x44d63f7b1e64fe52uL
    }

    private fun midContextStreamWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0xef8a98c3u + 0x5251f797u else u32Affine(word, index, 0x1183c8, 0x1171e8, t) * 0x9198bbe1u + 0x96d49925u
        val folded = fold63c278(w.toULong() * 0x10aca1fefeaea819uL + 0x791f2f89d18f0bccuL, 0x2ff6f0, 8, t)
        return w.toULong() * 0x7e3d39fbe4db207buL + folded * 0xf948d04d00000000uL + 0xefba822749ae8302uL
    }

    private fun midSPF0StreamWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x833f922fu + 0xb8f79a5cu else u32Affine(word, index, 0x121828, 0x114848, t) * 0xe9ed5087u + 0x99a662bcu
        val folded = fold63c278(w.toULong() * 0x9445eb5f6cc20c37uL + 0x2e115166fc9d38deuL, 0x2ff770, 8, t)
        return w.toULong() * 0x76037a61bba475bduL + folded * 0x7a18645500000000uL + 0xeb599af66ebe44f8uL
    }

    private fun midSP40BWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x68f1c9c3u + 0x75e3de3du else u32Affine(word, index, 0x121848, 0x113688, t) * 0x603eaaa7u + 0xf3704eb8u
        val folded = fold63c278(w.toULong() * 0x339d03216c178183uL + 0xccccddb48073e82duL, 0x2ff7f0, 8, t)
        return w.toULong() * 0x6255799ade203b13uL + folded * 0x504804cf00000000uL + 0xece1b0fccff7a5d6uL
    }

    private fun sixthAWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0xe12f8e63u + 0xed30a70du else u32Affine(word, index, 0x115e88, 0x11cd48, t) * 0x61e5762bu + 0xd79521cbu
        val folded = fold63c278(w.toULong() * 0x5ebafd23d4800453uL + 0xfce166cf66e4ed89uL, 0x2ff8f0, 8, t)
        return w.toULong() * 0xfe6b40e82ac2cfaduL + folded * 0x4b28a40100000000uL + 0x5ffded7fc281e70cuL
    }

    private fun sixthBWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x5f8eb06bu + 0x71dd9075u else u32Affine(word, index, 0x123d08, 0x122188, t) * 0x32ca6d69u + 0x5b73a719u
        val folded = fold63c278(w.toULong() * 0xf82a21269cc1d1dbuL + 0xd1172c1561159fb2uL, 0x2ff970, 8, t)
        return w.toULong() * 0xad5daa3cdd561923uL + folded * 0x5a9053a700000000uL + 0x06054c9125875977uL
    }

    private fun eighthAWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0xcbb0f5d5u + 0xbc0ef378u else u32Affine(word, index, 0x11a908, 0x116868, t) * 0xf4ade1bbu + 0x14498d6fu
        val folded = fold63c278(w.toULong() * 0x9e47779cb45c572fuL + 0x4d028e31657373f8uL, 0x2ffdb0, 8, t)
        return w.toULong() * 0xa08be5a120f8c447uL + folded * 0xc729619700000000uL + 0x6e392c9a885df52cuL
    }

    private fun eighthBWord(word: UInt, index: Int, t: Tables): ULong {
        val w = if (index == 0) word * 0x667888b5u + 0x8f0d98aeu else u32Affine(word, index, 0x115ea8, 0x118e68, t) * 0xea2a6db9u + 0x0a1fb246u
        val folded = fold63c278(w.toULong() * 0x5642541b8c3e3bb7uL + 0x9965e0d235e6c59buL, 0x2ffe30, 8, t)
        return w.toULong() * 0xb84f64edab558edduL + folded * 0x82850df500000000uL + 0xe5d3a90393662e86uL
    }

    private fun shiftedPrefixSumsU64(words: ULongArray): ULongArray {
        val out = ULongArray(words.size + 1)
        var total = 0uL
        for (i in words.indices) { total += words[i]; out[i + 1] = total }
        return out
    }

    private fun packU32LE(words: UIntArray): ByteArray {
        val out = ByteArray(words.size * 4)
        for (i in words.indices) { var v = words[i]; for (k in 0 until 4) { out[i * 4 + k] = (v and 0xffu).toByte(); v = v shr 8 } }
        return out
    }

    // ---- 63c278 branch loop + final schedule + scheduleWords ----

    val pre63c278Arg0Source: ByteArray = ints(
        0x21, 0xed, 0x7e, 0x8f, 0xc9, 0x86, 0x29, 0x76,
        0xac, 0x50, 0xb4, 0xcb, 0x1e, 0x31, 0xa9, 0x1f,
        0x30, 0xfa, 0x05, 0xc7, 0x06, 0x82, 0xac, 0x26,
        0xbc, 0x7d, 0xb7, 0x62, 0x19, 0xfd, 0x1d, 0x35,
        0x21, 0xed, 0x7e, 0x8f, 0xb9, 0x8b, 0xbe, 0x51,
        0xa3, 0x76, 0x9d, 0xa0, 0xc5, 0x08, 0x6c, 0x23,
        0x30, 0xfa, 0x05, 0xc7, 0x06, 0x82, 0xac, 0x26,
        0xbc, 0x7d, 0xb7, 0xd1, 0x19, 0xfd, 0x1d, 0x35,
        0x46, 0x68, 0x3b, 0x2a, 0x18, 0xd7, 0xe2, 0xe2,
        0xa3, 0x76, 0x9d, 0xa0, 0xc5, 0x08, 0x6c, 0x23,
        0x30, 0xfa, 0x05, 0xc7, 0x06, 0x82, 0xac, 0x26,
    )
    const val pre63c278Scalar: ULong = 0x33b7dca8cdf2d720uL

    private fun ints(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    class BranchResult63c278(val sp390: UIntArray, val sp440: UIntArray, val sp6b0: UIntArray, val sp658: UIntArray)

    fun builder63c278ScheduleWords(arg0: ByteArray, arg1: ByteArray, arg2: ByteArray, scalar: ULong): UIntArray {
        val (x1, x0) = builder63c278InitialVectors(arg0, arg1)
        val mixed = builder63c278ScalarMixVector(x1, x0, scalar)
        val tail1 = builder63c278Tail1U32Words(mixed)
        val (x2, x0b) = builder63c278SecondInitialVectors(arg0, arg2)
        val mixed2 = builder63c278ScalarMix2Vector(x2, x0b, scalar)
        val tail2 = builder63c278Tail2U32Words(mixed2)
        val accum = builder63c278AccumulatorStreams(arg2, tail2)
        val bridge = builder63c278BridgeConvolutionVector(accum)
        val bridgeX0 = builder63c278BridgeX0Vector(arg0)
        val bridgeMixed = builder63c278BridgeMixVector(bridge, bridgeX0, scalar)
        val sp128 = builder63c278BridgeSP128Words(bridgeMixed)
        val prebranch = builder63c278PrebranchInitialStreams(arg0, tail1, sp128)
        val pre4f0 = builder63c278PrebranchSP4F0Words(arg0)
        val pre230 = builder63c278PrebranchSP230Words(pre4f0)
        val pre5a0 = builder63c278PrebranchSP5A0Words(pre230)
        val finalLoop = builder63c278BranchLoop(arg0, prebranch.sp390, prebranch.sp440, prebranch.sp6b0, prebranch.sp658, pre5a0)
        return builder63c278FinalScheduleFromSP440U32(finalLoop.sp440)
    }

    fun builder63c278FinalScheduleFromSP440U32(sp440: UIntArray): UIntArray {
        val t = Tables.shared
        val staged = UIntArray(V63)
        for (index in 0 until V63) {
            val to = (index * 4) and 0x1c
            staged[index] = u32TblAffine(sp440[index], 0x117308 + to, 0x11b508 + to, t)
        }
        val out = UIntArray(20)
        for (index in 0 until 20) {
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(staged[index], 0x11f5e8 + to, 0x1154e8 + to, t)
        }
        return out
    }

    fun builder63c278BranchLoop(
        arg0: ByteArray, sp390W: UIntArray, sp440W: UIntArray, sp6b0W: UIntArray, sp658W: UIntArray, sp5a0W: UIntArray,
        maxIterations: Int = 2000,
    ): BranchResult63c278 {
        val t = Tables.shared
        var sp390 = sp390W.copyOf(); var sp440 = sp440W.copyOf(); var sp6b0 = sp6b0W.copyOf(); var sp658 = sp658W.copyOf()
        val sp5a0 = sp5a0W
        repeat(maxIterations) {
            if (terminalSP658Ready(sp658, t)) return BranchResult63c278(sp390, sp440, sp6b0, sp658)
            if (sp6b0[0] and 1u == 0u) {
                sp6b0 = branchAffine(sp6b0, sp6b0EvenBP, t)
                sp440 = loopUpdateSP440(sp440, sp5a0, t)
                return@repeat
            }
            while (sp658[0] and 1u != 0u) {
                sp658 = branchAffine(sp658, sp658OddBP, t)
                sp390 = loopUpdateSP390(sp390, sp5a0, t)
            }
            if (loopSP658EvenUsesSuccessPath(sp658, sp6b0, t)) {
                sp658 = stageReducer(
                    staticPatternWords(0x125a70, 0x125940, 0x126ce0, t),
                    listOf(sp658 to 0x11b4c8, sp6b0 to 0x122b08),
                    SP(0x6238179au, 0x2cb31cf5u, 0xeaa360b5u, 0x7dcae1fdu, 0x3025b8, 0x354589c9u, 0xb0000000u, 0xb0b43182u, 0x6f16c509u, 0x0e93af70u, 0x29fd0d1cu, 0x11d508, 0x123508), t,
                )
                if (predicate64D55C(sp440, sp390, t) == 0) sp390 = loopUpdateSP390PredicateFalse(sp390, arg0, t)
                sp390 = stageReducer(
                    staticPatternWords(0x124d90, 0x125c10, 0x126998, t),
                    listOf(sp390 to 0x11dd48, sp440 to 0x122288),
                    SP(0xd554336du, 0x43e12a11u, 0xfd350b93u, 0xc2fdb2e2u, 0x302638, 0x419ce971u, 0x50000000u, 0x967ae928u, 0xcbd75debu, 0x428a2150u, 0x27f798a1u, 0x118f48, 0x11ec28), t,
                )
            } else {
                sp6b0 = stageReducer(
                    staticPatternWords(0x1256b0, 0x125f40, 0x1268d8, t),
                    listOf(sp6b0 to 0x1149c8, sp658 to 0x11f5c8),
                    SP(0x2b0fe6d9u, 0x346b3047u, 0xe8d292cbu, 0x6376b766u, 0x3024b8, 0xd98513dbu, 0xf0000000u, 0x8af9de6du, 0x74f0e285u, 0xb0f1d7b0u, 0x6ac588e9u, 0x11fdc8, 0x115f68), t,
                )
                if (predicate64D55C(sp440, sp390, t) != 0) sp440 = loopUpdateSP440PredicateTrue(sp440, arg0, t)
                sp440 = stageReducer(
                    staticPatternWords(0x125440, 0x126030, 0x1267b8, t),
                    listOf(sp440 to 0x114068, sp390 to 0x11c488),
                    SP(0xf35277e4u, 0x3ae4bb05u, 0x130a19ebu, 0x624d99a5u, 0x302578, 0x53cea2cfu, 0x30000000u, 0x81d9862eu, 0x53c4f527u, 0xc3b0ad90u, 0x59871186u, 0x120f68, 0x11a088), t,
                )
            }
        }
        throw FirstPairSliceException("63c278 branch loop did not terminate")
    }

    private fun loopUpdateSP440(words: UIntArray, sp5a0: UIntArray, t: Tables): UIntArray {
        if (words[0] and 1u != 0u) return branchAffine(words, sp440OddBP, t)
        val sp4f0 = branchAffine(words, sp440EvenSP4F0BP, t)
        return stageReducer(
            staticPatternWords(0x124f10, 0x125930, 0x126cd8, t),
            listOf(sp4f0 to 0x114988, sp5a0 to 0x11aa08),
            SP(0xd3f16146u, 0x84bb8555u, 0xd3dd75bbu, 0x4bdc02a1u, 0x302338, 0x26bbb9ffu, 0x30000000u, 0xe8f27692u, 0xef9fd9a7u, 0x06026590u, 0x613a18d6u, 0x120688, 0x1185a8), t,
        )
    }

    private fun loopUpdateSP390(words: UIntArray, sp5a0: UIntArray, t: Tables): UIntArray {
        if (words[0] and 1u != 0u) {
            val sp4f0 = branchAffine(words, sp390OddSP4F0BP, t)
            return stageReducer(
                staticPatternWords(0x126330, 0x126020, 0x126a10, t),
                listOf(sp4f0 to 0x11ce08, sp5a0 to 0x122ae8),
                SP(0x1cd91585u, 0x1a4cb35bu, 0x5137a735u, 0x3e9907e2u, 0x302438, 0x38fc5a19u, 0xb0000000u, 0x0f3d7c5du, 0xa81b54e7u, 0x7e4ab190u, 0x767d913cu, 0x11a068, 0x11b4a8), t,
            )
        }
        return branchAffine(words, sp390EvenBP, t)
    }

    private fun loopUpdateSP390PredicateFalse(sp390: UIntArray, arg0: ByteArray, t: Tables): UIntArray =
        stageReducer(
            staticPatternWords(0x126040, 0x126600, 0x126740, t),
            listOf(sp390 to 0x11b4e8, arg0Words(arg0) to 0x11bc28),
            SP(0x6306d080u, 0x90b4d58bu, 0x323154f1u, 0x154382eeu, 0x3025f8, 0x30b9cbfbu, 0x50000000u, 0x61849d3du, 0x1fb5a053u, 0x04a5fad0u, 0x002fe7efu, 0x117be8, 0x1172e8), t,
        )

    private fun loopUpdateSP440PredicateTrue(sp440: UIntArray, arg0: ByteArray, t: Tables): UIntArray =
        stageReducer(
            staticPatternWords(0x124f20, 0x125a00, 0x126c68, t),
            listOf(sp440 to 0x112668, arg0Words(arg0) to 0x116928),
            SP(0x43bff476u, 0x8123c767u, 0xbc55d64fu, 0x3db88f4fu, 0x302538, 0xb7e919a9u, 0x90000000u, 0xd881235bu, 0x239e1779u, 0xc61e8870u, 0xa1d86ec1u, 0x113728, 0x116948), t,
        )

    private fun terminalSP658Ready(sp658: UIntArray, t: Tables): Boolean {
        if ((sp658[0].toULong() * 0x04dc738duL).toUInt() != 0x49f4222fu) return false
        for (index in 1 until V63) {
            val to = (index * 4) and 0x1c
            val check = (foldTbl(0x3021a0 + index * 4, t).toULong() * u32Tbl(0x1234e8 + to, t).toULong() +
                sp658[index].toULong() * u32Tbl(0x120668 + to, t).toULong() +
                u32Tbl(0x11e5a8 + to, t).toULong()).toUInt()
            if (check != 0x0a2c3abeu) return false
        }
        return true
    }

    private fun loopSP658EvenUsesSuccessPath(sp658: UIntArray, sp6b0: UIntArray, t: Tables): Boolean {
        for (index in V63 - 1 downTo 0) {
            val to = (index * 4) and 0x1c
            val check = (sp658[index].toULong() * u32Tbl(0x1154c8 + to, t).toULong() +
                sp6b0[index].toULong() * u32Tbl(0x1172c8 + to, t).toULong() +
                u32Tbl(0x11c468 + to, t).toULong()).toUInt()
            if (check != 0x59262fedu) {
                val folded = fold32ByNibbles63c278(check, 0x302478, 7, t)
                return (folded and 0x0fu) == 0x0du
            }
        }
        return true
    }

    private fun predicate64D55C(sp440: UIntArray, sp390: UIntArray, t: Tables): Int {
        for (index in V63 - 1 downTo 0) {
            val to = (index * 4) and 0x1c
            val check = (sp440[index].toULong() * u32Tbl(0x11fde8 + to, t).toULong() +
                sp390[index].toULong() * u32Tbl(0x1206c8 + to, t).toULong() +
                u32Tbl(0x1185c8 + to, t).toULong()).toUInt()
            if (check != 0x213734c0u) {
                val folded = fold32ByNibbles63c278(check, 0x3024f8, 7, t)
                return if ((folded and 0x0fu) != 0u) 1 else 0
            }
        }
        return 0
    }

    // branch-affine engine
    private class BP(
        val arg0Mul: UInt, val arg0Add: UInt, val halfMul: UInt, val bitTable: Int, val preMul: UInt, val preAdd: UInt,
        val wordMul: UInt, val wordAdd: UInt, val foldTable: Int, val selectTable: Int, val argMulTable: Int, val argAddTable: Int,
        val carryMul: UInt, val valueMul: UInt, val nextMul: UInt, val loopAdd: UInt, val finalAdd: UInt, val outMulTable: Int, val outAddTable: Int,
    )
    private class SP(
        val carry: UInt, val carryMul: UInt, val preMul: UInt, val preAdd: UInt, val foldTable: Int, val reduceMul: UInt,
        val sideMul: UInt, val reduceAdd: UInt, val folded7Mul: UInt, val folded8Mul: UInt, val nextAdd: UInt, val outMulTable: Int, val outAddTable: Int,
    )

    private val sp658OddBP = BP(0x33f71427u, 0x58500b33u, 0x2cb60683u, 0x126a48, 0xc4fb260bu, 0xf348f6f7u, 0x2b1d86b1u, 0xfa05b11du, 0x302378, 0x112648, 0x117ba8, 0x11cde8, 0xa822376du, 0xb8000000u, 0x30000000u, 0x24e24246u, 0x14e24246u, 0x1206a8, 0x11b468)
    private val sp6b0EvenBP = BP(0x96928029u, 0x666d5b3au, 0x27acf74du, 0x126a18, 0x84602417u, 0xf95f2c9du, 0x4bd20bc9u, 0x3a0734cau, 0x302278, 0x11cda8, 0x11c428, 0x119748, 0x96d2d627u, 0x98000000u, 0x90000000u, 0x2f40aa3du, 0x3f40aa3du, 0x117b68, 0x121968)
    private val sp440OddBP = BP(0x28f734a3u, 0x7fc88b1cu, 0xb00c4591u, 0x126780, 0x059e578du, 0x33273af5u, 0x9d8dd89fu, 0xa52d9347u, 0x3022b8, 0x113708, 0x11d4e8, 0x11c448, 0xd470f3b3u, 0xe8000000u, 0xd0000000u, 0xadcd0df0u, 0x65cd0df0u, 0x11cdc8, 0x117b88)
    private val sp440EvenSP4F0BP = BP(0x5888f7f5u, 0xbbf0e3d5u, 0x642326dbu, 0x126858, 0x246654c1u, 0x2e782dc3u, 0x1101c103u, 0x183fafb9u, 0x3022f8, 0x1154a8, 0x11a9e8, 0x122268, 0x4d140725u, 0xa8000000u, 0xb0000000u, 0x6fe563d6u, 0x8fe563d6u, 0x11dd28, 0x120f48)
    private val sp390EvenBP = BP(0x5b7c4419u, 0xd8c9cb43u, 0xa30de075u, 0x1267f0, 0x936efcedu, 0x32c3c0a7u, 0x88e44053u, 0xc35d94bbu, 0x3023b8, 0x1149a8, 0x11b488, 0x119768, 0x14c37dcdu, 0x18000000u, 0x30000000u, 0xe4da180fu, 0xccda180fu, 0x1168e8, 0x11a048)
    private val sp390OddSP4F0BP = BP(0x8e39b739u, 0x7c6d92a6u, 0xdca8620du, 0x126940, 0x17c4f57fu, 0x5b647db4u, 0xb0fff815u, 0x831b4fffu, 0x3023f8, 0x123e28, 0x11f5a8, 0x11bc08, 0x19f6ba67u, 0xb8000000u, 0x90000000u, 0x85a9b64du, 0xb5a9b64du, 0x117bc8, 0x116908)

    private fun branchAffine(words: UIntArray, p: BP, t: Tables): UIntArray {
        val first = (words[0].toULong() * p.arg0Mul.toULong() + p.arg0Add.toULong()).toUInt()
        val firstState = branchState(first, p.halfMul, p.bitTable, t)
        var carry = branchWord(firstState, p, t)
        val out = UIntArray(V63)
        for (index in 0 until V63 - 1) {
            val nextIndex = index + 1
            val nto = (nextIndex and 7) * 4
            val value = u32TblAffine(words[nextIndex], p.argMulTable + nto, p.argAddTable + nto, t)
            val state = branchState(value, p.halfMul, p.bitTable, t)
            val word = branchWord(state, p, t)
            carry = (carry.toULong() * p.carryMul.toULong() + value.toULong() * p.valueMul.toULong() + word.toULong() * p.nextMul.toULong()).toUInt()
            val storeValue = carry + p.loopAdd
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(storeValue, p.outMulTable + to, p.outAddTable + to, t)
            carry = word
        }
        val finalStore = (carry.toULong() * p.carryMul.toULong() + p.finalAdd.toULong()).toUInt()
        val fto = ((V63 - 1) * 4) and 0x1c
        out[V63 - 1] = u32TblAffine(finalStore, p.outMulTable + fto, p.outAddTable + fto, t)
        return out
    }

    private fun branchWord(state: UInt, p: BP, t: Tables): UInt {
        val preFold = (state.toULong() * p.preMul.toULong() + p.preAdd.toULong()).toUInt()
        val select = branchSelectBit(preFold, p.foldTable, p.selectTable, t)
        return (state.toULong() * p.wordMul.toULong() + p.wordAdd.toULong() + select.toULong()).toUInt()
    }

    private fun branchSelectBit(value: UInt, foldTable: Int, selectTable: Int, t: Tables): UInt {
        val folded = fold32ByNibbles63c278(value, foldTable, 7, t)
        val selected = u32Tbl(selectTable + (folded and 7u).toInt() * 4, t) + (folded shr 3)
        return selected shl 31
    }

    private fun branchState(word: UInt, halfMul: UInt, bitTable: Int, t: Tables): UInt {
        val bit = u32Tbl(bitTable + (word and 1u).toInt() * 4, t)
        return ((word shr 1).toULong() * halfMul.toULong() + bit.toULong()).toUInt()
    }

    private fun staticPatternWords(q0: Int, q1: Int, tail: Int, t: Tables): UIntArray {
        val q0w = UIntArray(4) { u32Tbl(q0 + it * 4, t) }
        val q1w = UIntArray(4) { u32Tbl(q1 + it * 4, t) }
        val tailw = UIntArray(2) { u32Tbl(tail + it * 4, t) }
        return (q0w.toList() + q1w.toList() + q0w.toList() + q1w.toList() + q0w.toList() + tailw.toList()).toUIntArray()
    }

    private fun arg0Words(arg0: ByteArray): UIntArray = UIntArray(V63) { rdU32(arg0, it * 4) }

    private fun stageReducer(staticWords: UIntArray, streams: List<Pair<UIntArray, Int>>, p: SP, t: Tables): UIntArray {
        val sp230 = staticWords.copyOf()
        for ((words, mulTable) in streams) {
            for (index in 0 until V63) {
                val to = (index and 7) * 4
                val mul = u32Tbl(mulTable + to, t)
                sp230[index] = (sp230[index].toULong() + words[index].toULong() * mul.toULong()).toUInt()
            }
        }
        var carry = p.carry
        val out = UIntArray(V63)
        for (index in 0 until V63) {
            val word = sp230[index]
            carry = (carry.toULong() * p.carryMul.toULong() + word.toULong()).toUInt()
            var folded7 = (carry.toULong() * p.preMul.toULong() + p.preAdd.toULong()).toUInt()
            folded7 = fold32ByNibbles63c278(folded7, p.foldTable, 7, t)
            val folded8 = foldTbl(p.foldTable + (folded7 and 0x0fu).toInt() * 4, t) + (folded7 shr 4)
            val stage = (carry.toULong() * p.reduceMul.toULong() + folded7.toULong() * p.sideMul.toULong() + p.reduceAdd.toULong()).toUInt()
            val nextCarry = (folded7.toULong() * p.folded7Mul.toULong() + folded8.toULong() * p.folded8Mul.toULong()).toUInt()
            val to = (index * 4) and 0x1c
            out[index] = u32TblAffine(stage, p.outMulTable + to, p.outAddTable + to, t)
            carry = nextCarry + p.nextAdd
        }
        return out
    }

    // ---- pack helpers ----

    private fun packDF80Zeros6Marker(marker: Int, src: ByteArray): ByteArray =
        ByteArray(6) + byteArrayOf(marker.toByte()) + src.copyOfRange(0, 11)

    private fun packDF80Zeros5Marker6(src: ByteArray): ByteArray =
        ByteArray(5) + byteArrayOf(6) + src.copyOfRange(0, 12)

    private fun packDF80Zeros11Marker5(src: ByteArray): ByteArray =
        ByteArray(11) + byteArrayOf(5) + src.copyOfRange(0, 6)

    private fun packDF80Zeros12Marker3(src: ByteArray): ByteArray =
        ByteArray(12) + byteArrayOf(3) + src.copyOfRange(0, 5)

    private fun packDF80Zeros8Zero6(src: ByteArray): ByteArray =
        ByteArray(9) + byteArrayOf(6) + src.copyOfRange(0, 8)

    private fun packDF80Zeros2Marker6(src: ByteArray): ByteArray =
        byteArrayOf(0, 0, 6) + src.copyOfRange(0, 15)

    private fun packDF80Zeros14Marker1(src: ByteArray): ByteArray =
        ByteArray(14) + byteArrayOf(1) + src.copyOfRange(0, 3)

    private fun packDF80Zeros9(src: ByteArray): ByteArray =
        ByteArray(9) + src.copyOfRange(0, 9)

    private fun packDF80Zeros4Marker3(src: ByteArray): ByteArray =
        ByteArray(4) + byteArrayOf(3) + src.copyOfRange(0, 13)

    // ---- VM core ----

    /** sbox19-driven step. src1/src2 are byte values (0..255) or null (absent). */
    private fun step(state: Int, src1: Int?, src2: Int?, prog: Int, t: Tables): Int {
        var idx = state and 0xf8
        if (src1 != null) idx = idx xor (src1 and 0xff)
        if (src2 != null) idx = idx or ((src2 and 0xff) shl 8)
        idx = idx xor ((prog and 0xff) shl 11)
        return t.sbox19[idx].toInt() and 0xff
    }

    private fun vm67cc18(magic: Long, src1: ByteArray, src2: ByteArray, t: Tables): ByteArray {
        val progOff = (magic and 0x3fffffL).toInt()
        val count = ((magic ushr 36) and 0x3fffL).toInt()
        val tail = (magic ushr 50).toInt()
        val total = count + tail
        val prog = checkedSlice(t.prog67cc18, progOff, total)
        val out = ByteArray(total)
        var state = 0
        for (i in 0 until count) {
            state = step(state, src1[i].toInt() and 0xff, src2[i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        for (i in 0 until tail) {
            val pos = count + i
            state = step(state, null, src2[pos].toInt() and 0xff, prog[pos].toInt() and 0xff, t)
            out[pos] = (state and 7).toByte()
        }
        return out
    }

    private fun vm67cecc(magic: Long, src1: ByteArray, src2: ByteArray, t: Tables): ByteArray {
        val progOff = (magic and 0x3fffffL).toInt()
        val primer = ((magic ushr 22) and 0x3fffL).toInt()
        val count = ((magic ushr 36) and 0x3fffL).toInt()
        val tail = (magic ushr 50).toInt()
        val prog = checkedSlice(t.prog67cc18, progOff, primer + count + tail)
        var state = 0
        for (i in 0 until primer) {
            state = step(state, src1[i].toInt() and 0xff, src2[i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
        }
        val out = ByteArray(count + tail)
        for (i in 0 until count) {
            val pos = primer + i
            state = step(state, src1[pos].toInt() and 0xff, src2[pos].toInt() and 0xff, prog[pos].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        for (i in 0 until tail) {
            val pos = primer + count + i
            state = step(state, null, null, prog[pos].toInt() and 0xff, t)
            out[count + i] = (state and 7).toByte()
        }
        return out
    }

    private fun step16Masked(state: Int, src: Int, prog: Int, t: Tables): Int {
        val byteOffset = (((state and 0xff8) xor (src and 0xff)) shl 1) or ((prog and 0xff) shl 13)
        return ttableBHalfword(byteOffset, t)
    }

    private fun step16Full(state: Int, src: Int, prog: Int, t: Tables): Int {
        val byteOffset = ((prog and 0xff) shl 13) xor (((state xor (src and 0xff)) shl 1))
        return ttableBHalfword(byteOffset, t)
    }

    private fun ttableBHalfword(byteOffset: Int, t: Tables): Int {
        if (byteOffset < 0 || byteOffset + 1 >= t.ttableBExt.size) throw FirstPairSliceException("ttableBExt OOB $byteOffset")
        return (t.ttableBExt[byteOffset].toInt() and 0xff) or ((t.ttableBExt[byteOffset + 1].toInt() and 0xff) shl 8)
    }

    fun vm67d524(magic: Long, src: ByteArray, t: Tables): ByteArray {
        val progOff = (magic and 0x3fffffL).toInt()
        val primer = ((magic ushr 22) and 0x3fffL).toInt()
        val count = ((magic ushr 36) and 0x3fffL).toInt()
        val prog = checkedSlice(t.prog67cc18, progOff, primer + count + 3)
        var state = 0
        for (i in 0 until primer) state = step16Masked(state, src[i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
        val out = ByteArray(count + 3)
        for (i in 0 until count) {
            state = step16Masked(state, src[primer + i].toInt() and 0xff, prog[primer + i].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        val tailProg = primer + count
        for (i in 0 until 3) {
            state = step16Full(state, src[2 + i].toInt() and 0xff, prog[tailProg + i].toInt() and 0xff, t)
            out[count + i] = (state and 7).toByte()
        }
        return out
    }

    private fun vm67076c(magic: Long, src1: ByteArray, src2: ByteArray, t: Tables): ByteArray {
        val progOff = (magic and 0x3fffffL).toInt()
        val prog = checkedSlice(t.prog67076c, progOff, BLOCK66_SIZE)
        val out = ByteArray(BLOCK66_SIZE)
        var state = 0
        for (i in 0 until BLOCK66_SIZE) {
            state = step(state, src1[i].toInt() and 0xff, src2[i].toInt() and 0xff, prog[i].toInt() and 0xff, t)
            out[i] = (state and 7).toByte()
        }
        return out
    }

    // ---- helpers ----

    private fun checkedSlice(bytes: ByteArray, offset: Int, count: Int): ByteArray {
        if (offset < 0 || count < 0 || offset + count > bytes.size) throw FirstPairSliceException("slice OOB off=$offset count=$count size=${bytes.size}")
        return bytes.copyOfRange(offset, offset + count)
    }

    // ---- constants ----

    private const val BLOCK66_SIZE = 0x42
    private const val DF80_WORD_SIZE = 0x12
    private const val DF80_SCHEDULE_SIZE = 0x480
    private const val DF80_STATE_SIZE = 8 * DF80_WORD_SIZE
    private const val DF80_INPUT_BLOCK_COUNT = 4
    private const val DF80_DERIVED_SCHEDULE_SIZE = 0x360
    private const val DF80_INITIAL_WORKSPACE_STRIDE = 0x48
    private const val DF80_INITIAL_WORKSPACE_SIZE = 0x120

    // ---- table loader (23 tables shared by all builder layers) ----

    internal class Tables private constructor() {
        val sbox19 = load("sbox_19bit_lib_986819")
        val prog64e2b8 = load("firstpair_prog_64e2b8_3041b4")
        val prog638840 = load("firstpair_prog_638840_2f5046")
        val lowSeedStatics6388f0 = load("firstpair_6388f0_low_seed_statics_2f4d28")
        val lowLoopStatics6388f0 = load("firstpair_6388f0_low_loop_statics_2fe600")
        val laneTables6388f0 = load("firstpair_6388f0_lane_tables_302678")
        val selectorMul6388f0 = load("firstpair_6388f0_selector_mul_116968")
        val selectorAdd6388f0 = load("firstpair_6388f0_selector_add_119788")
        val u32Tables63c278 = load("firstpair_63c278_u32_tables_112588")
        val foldTables63c278 = load("firstpair_63c278_fold_tables_2feb18")
        val tailFoldTables633fa8 = load("firstpair_633fa8_tail_fold_tables_2fe798")
        val tailU32LowTables633fa8 = load("firstpair_633fa8_tail_u32_low_tables_112528")
        val nullTables633fa8 = load("firstpair_633fa8_null_tables_2fd1f1")
        val nullNibble633fa8 = load("firstpair_633fa8_null_nibble_303a14")
        val process2PublicTables = load("firstpair_process2_public_tables_3038c0")
        val prog67cc18 = load("firstpair_prog_67cc18_369862")
        val ttableBExt = load("child23_ttable_b_ext_976ea8_100000")
        val finalLenTables = load("firstpair_final_len_tables_372102")
        val df80RoundTables = load("firstpair_df80_round_tables_37120e")
        val finalizerTables = load("firstpair_finalizer_tables_370e30")
        val seedTables679f48 = load("firstpair_679f48_seed_tables_37075e")
        val reducer67ea28Nibble = load("firstpair_reducer67ea28_nibble_373cf4")
        val prog67076c = load("firstpair_prog_67076c_35d3ef")
        val sharedContext6388f0 = load("firstpair_6388f0_shared_context_2cdae1")
        val callerLoopInterleaved6388f0 = load("firstpair_6388f0_caller_loop_interleaved_2cdfa9")

        companion object {
            val shared: Tables by lazy { Tables() }
            private fun load(name: String): ByteArray {
                val s = Tables::class.java.getResourceAsStream("/runtime_tables/$name.bin")
                    ?: throw FirstPairSliceException("missing runtime table $name")
                return s.use { it.readBytes() }
            }
        }
    }
}

class FirstPairSliceException(message: String) : Exception(message)
