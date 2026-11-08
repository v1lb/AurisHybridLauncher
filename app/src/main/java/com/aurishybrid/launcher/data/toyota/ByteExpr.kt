package com.aurishybrid.launcher.data.toyota

/**
 * Формулы в стиле Torque: A = 1-й байт данных, …, Z = 26-й, AA = 27-й, AB = 28-й …
 * Поддерживается: + - * / ( ), числа, SIGNED(x) (8-битное со знаком), {A:n} — бит n байта A.
 * Пример: "(A*256+B)/10-40", "SIGNED(C)*0.5", "{D:3}".
 */
class ByteExpr private constructor(private val root: Node, val text: String) {

    /** null — в ответе не хватает байтов или результат не число. */
    fun eval(bytes: IntArray): Double? = try {
        root.eval(bytes).takeIf { it.isFinite() }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private sealed interface Node {
        fun eval(b: IntArray): Double
    }

    private class Num(val v: Double) : Node {
        override fun eval(b: IntArray) = v
    }

    private class ByteRef(val i: Int) : Node {
        override fun eval(b: IntArray) = b[i].toDouble()
    }

    private class Bit(val i: Int, val bit: Int) : Node {
        override fun eval(b: IntArray) = ((b[i] shr bit) and 1).toDouble()
    }

    private class Signed(val n: Node) : Node {
        override fun eval(b: IntArray): Double {
            val x = n.eval(b)
            return if (x >= 128.0) x - 256.0 else x
        }
    }

    private class Neg(val n: Node) : Node {
        override fun eval(b: IntArray) = -n.eval(b)
    }

    private class Bin(val op: Char, val l: Node, val r: Node) : Node {
        override fun eval(b: IntArray): Double {
            val x = l.eval(b)
            val y = r.eval(b)
            return when (op) {
                '+' -> x + y
                '-' -> x - y
                '*' -> x * y
                else -> x / y
            }
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseAll(): Node {
            val n = expr()
            skip()
            if (i != s.length) error("лишний символ '${s[i]}' в позиции $i")
            return n
        }

        private fun skip() {
            while (i < s.length && s[i] == ' ') i++
        }

        private fun peek(): Char? {
            skip(); return if (i < s.length) s[i] else null
        }

        private fun expr(): Node {
            var n = term()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') {
                    i++; n = Bin(c, n, term())
                } else return n
            }
        }

        private fun term(): Node {
            var n = factor()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/') {
                    i++; n = Bin(c, n, factor())
                } else return n
            }
        }

        private fun factor(): Node {
            val c = peek() ?: error("неожиданный конец формулы")
            return when {
                c == '-' -> { i++; Neg(factor()) }
                c == '(' -> { i++; val n = expr(); expect(')'); n }
                c == '{' -> {
                    i++
                    val idx = letters(ident())
                    expect(':')
                    val bit = number().toInt()
                    expect('}')
                    Bit(idx, bit)
                }
                c.isDigit() || c == '.' -> Num(number())
                c.isLetter() -> {
                    val name = ident()
                    if (name == "SIGNED") {
                        expect('('); val n = expr(); expect(')'); Signed(n)
                    } else ByteRef(letters(name))
                }
                else -> error("неожиданный символ '$c'")
            }
        }

        private fun expect(c: Char) {
            if (peek() != c) error("ожидалось '$c'")
            i++
        }

        private fun ident(): String {
            skip()
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            return s.substring(start, i).uppercase()
        }

        private fun number(): Double {
            skip()
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDoubleOrNull() ?: error("неверное число")
        }

        private fun letters(name: String): Int = when (name.length) {
            1 -> name[0] - 'A'
            2 -> 26 * (name[0] - 'A' + 1) + (name[1] - 'A')
            else -> error("неизвестное имя '$name'")
        }
    }

    companion object {
        /** Бросает IllegalStateException с описанием ошибки формулы. */
        fun parse(src: String): ByteExpr = ByteExpr(Parser(src).parseAll(), src)
    }
}
