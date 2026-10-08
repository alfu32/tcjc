package tcc.kt

/** Ordered self-test runner from tests/tcctest.c. */
object TccTest {
    private val tests = listOf(
        "whitespace_test",
        "macro_test",
        "recursive_macro_test",
        "string_test",
        "expr_test",
        "scope_test",
        "scope2_test",
        "forward_test",
        "funcptr_test",
        "if_test",
        "loop_test",
        "switch_test",
        "goto_test",
        "enum_test",
        "typedef_test",
        "struct_test",
        "array_test",
        "expr_ptr_test",
        "bool_test",
        "optimize_out_test",
        "expr2_test",
        "constant_expr_test",
        "expr_cmp_test",
        "char_short_test",
        "init_test",
        "compound_literal_test",
        "kr_test",
        "struct_assign_test",
        "cast_test",
        "bitfield_test",
        "c99_bool_test",
        "float_test",
        "longlong_test",
        "manyarg_test",
        "stdarg_test",
        "relocation_test",
        "old_style_function_test",
        "alloca_test",
        "c99_vla_test",
        "sizeof_test",
        "typeof_test",
        "statement_expr_test",
        "local_label_test",
        "asm_test",
        "builtin_test",
        "weak_test",
        "global_data_test",
        "cmp_comparison_test",
        "math_cmp_test",
        "callsave_test",
        "builtin_frame_address_test",
        "volatile_test",
        "attrib_test",
        "bounds_check1_test",
        "func_arg_test"
    )

    /** Runs tests in the same order as the C program's main function. */
    @JvmStatic
    fun run(runTest: (String) -> Unit): Int {
        for (test in tests) {
            println("---- $test ----")
            runTest(test)
            println()
        }
        return 0
    }

    @JvmStatic
    fun testNames(): List<String> = tests.toList()
}
