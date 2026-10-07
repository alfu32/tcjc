package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.ir.AbiLocation
import org.tinycc.core.ir.CallingConventionDescriptor
import org.tinycc.core.ir.CallingConventionPlanner
import org.tinycc.core.ir.IrArchitecture
import org.tinycc.core.ir.IrRelocationKind
import org.tinycc.core.ir.IrRelocationTable
import org.tinycc.core.ir.IrRegister
import org.tinycc.core.ir.IrRegisterBank
import org.tinycc.core.ir.IrRegisterClass
import org.tinycc.core.ir.IrSectionFlags
import org.tinycc.core.ir.IrSectionKind
import org.tinycc.core.ir.IrSectionTable
import org.tinycc.core.ir.IrSymbol
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrTypes
import org.tinycc.core.ir.StackFrameBuilder

class BackendContractsTest {
    @Test
    fun plansRegisterAndStackArgumentsDeterministically() {
        val registers = listOf(
            IrRegister("r0", 0, IrRegisterClass.INTEGER, 64, callerSaved = true),
            IrRegister("r1", 1, IrRegisterClass.INTEGER, 64, callerSaved = true),
            IrRegister("f0", 2, IrRegisterClass.FLOAT, 64, callerSaved = true),
        )
        val convention = CallingConventionDescriptor(
            "test-abi", IrArchitecture.X86_64, pointerBits = 64, stackAlignment = 16,
            integerArgumentRegisters = registers.take(2),
            floatingArgumentRegisters = listOf(registers[2]),
            integerReturnRegisters = listOf(registers[0]),
        )
        val assignments = CallingConventionPlanner(convention).assignArguments(
            listOf(IrTypes.i32, IrTypes.f64, IrTypes.i64, IrTypes.i32),
        )

        assertEquals("r0", (assignments[0].location as AbiLocation.Register).value.name)
        assertEquals("f0", (assignments[1].location as AbiLocation.Register).value.name)
        assertEquals("r1", (assignments[2].location as AbiLocation.Register).value.name)
        assertTrue(assignments[3].location is AbiLocation.Stack)
    }

    @Test
    fun alignsStackSlotsAndKeepsSectionsOrdered() {
        val frame = StackFrameBuilder(16)
        assertEquals(0L, frame.allocate(4, 4).offset)
        assertEquals(16L, frame.allocate(8, 16).offset)
        assertEquals(32L, frame.finish().size)

        val sections = IrSectionTable()
        val text = sections.getOrCreate(".text", IrSectionKind.TEXT, IrSectionFlags(true, false, true))
        val data = sections.getOrCreate(".data", IrSectionKind.DATA, IrSectionFlags(true, true, false))
        assertEquals(0L, text.append(byteArrayOf(1, 2), 4).offset)
        assertEquals(4L, text.append(byteArrayOf(3), 4).offset)
        data.reserve(8, 8)
        assertEquals(listOf(".text", ".data"), sections.snapshots().map { it.name })
        assertEquals(byteArrayOf(1, 2, 0, 0, 3).toList(), text.snapshot().contents.toList())
    }

    @Test
    fun validatesRelocationWidthsAndSectionRequirements() {
        val sections = IrSectionTable()
        val text = sections.getOrCreate(".text", IrSectionKind.TEXT, IrSectionFlags(true, false, true))
        val data = sections.getOrCreate(".data", IrSectionKind.DATA, IrSectionFlags(true, true, false))
        text.append(ByteArray(8))
        data.append(ByteArray(8))
        val symbol = IrSymbol("target", IrType.Pointer(IrTypes.i8))
        val relocations = IrRelocationTable(sections)
        assertNotNull(relocations.add(".text", 0, 4, IrRelocationKind.PC_RELATIVE, symbol))
        assertFailsWith<IllegalArgumentException> {
            relocations.add(".data", 0, 4, IrRelocationKind.PC_RELATIVE, symbol)
        }
        assertFailsWith<IllegalStateException> {
            relocations.add(".text", 0, 4, IrRelocationKind.GOT, symbol)
        }
    }

    @Test
    fun registerBankRejectsAmbiguousPhysicalRegisters() {
        assertFailsWith<IllegalArgumentException> {
            IrRegisterBank(
                IrArchitecture.ARM64,
                listOf(
                    IrRegister("x0", 0, IrRegisterClass.INTEGER, 64, callerSaved = true),
                    IrRegister("duplicate", 0, IrRegisterClass.INTEGER, 64, callerSaved = true),
                ),
            )
        }
    }
}
