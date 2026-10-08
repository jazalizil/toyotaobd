package com.example.toyotaobd.obd

class FormulaException(message: String) : Exception(message)

/**
 * Formule de conversion au format « Torque » : `((A*256)+B)/80`, `A-40`, ...
 *
 * Les lettres A à Z désignent les octets de données de la réponse, après l'écho du
 * service et de l'identifiant. Opérateurs supportés : `+ - * /`, parenthèses, nombres décimaux.
 */
class Formula(val expression: String) {

    private sealed interface Node {
        fun eval(bytes: List<Int>): Double
    }

    private class Num(val value: Double) : Node {
        override fun eval(bytes: List<Int>) = value
    }

    private class Var(val name: Char) : Node {
        override fun eval(bytes: List<Int>): Double {
            val index = name - 'A'
            if (index >= bytes.size) throw FormulaException("Octet $name absent de la réponse (${bytes.size} octet(s) reçus)")
            return bytes[index].toDouble()
        }
    }

    private class Neg(val operand: Node) : Node {
        override fun eval(bytes: List<Int>) = -operand.eval(bytes)
    }

    private class Binary(val op: Char, val left: Node, val right: Node) : Node {
        override fun eval(bytes: List<Int>): Double {
            val l = left.eval(bytes)
            val r = right.eval(bytes)
            return when (op) {
                '+' -> l + r
                '-' -> l - r
                '*' -> l * r
                else -> if (r == 0.0) throw FormulaException("Division par zéro") else l / r
            }
        }
    }

    private val root: Node
    private var pos = 0
    private val src = expression.uppercase().filterNot { it.isWhitespace() }

    init {
        if (src.isEmpty()) throw FormulaException("Formule vide")
        root = parseExpression()
        if (pos < src.length) throw FormulaException("Caractère inattendu « ${src[pos]} »")
    }

    /** Plus grand index d'octet utilisé (A = 1), pour savoir combien d'octets sont attendus. */
    val bytesNeeded: Int = src.filter { it in 'A'..'Z' }.maxOfOrNull { it - 'A' + 1 } ?: 0

    fun evaluate(bytes: List<Int>): Double = root.eval(bytes)

    private fun parseExpression(): Node {
        var node = parseTerm()
        while (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
            val op = src[pos++]
            node = Binary(op, node, parseTerm())
        }
        return node
    }

    private fun parseTerm(): Node {
        var node = parseFactor()
        while (pos < src.length && (src[pos] == '*' || src[pos] == '/')) {
            val op = src[pos++]
            node = Binary(op, node, parseFactor())
        }
        return node
    }

    private fun parseFactor(): Node {
        if (pos >= src.length) throw FormulaException("Formule incomplète")
        val c = src[pos]
        return when {
            c == '-' -> { pos++; Neg(parseFactor()) }
            c == '+' -> { pos++; parseFactor() }
            c == '(' -> {
                pos++
                val inner = parseExpression()
                if (pos >= src.length || src[pos] != ')') throw FormulaException("Parenthèse fermante manquante")
                pos++
                inner
            }
            c.isDigit() || c == '.' -> {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                Num(src.substring(start, pos).toDoubleOrNull() ?: throw FormulaException("Nombre invalide"))
            }
            c in 'A'..'Z' -> {
                pos++
                if (pos < src.length && src[pos] in 'A'..'Z') throw FormulaException("Variable inconnue, utilisez A à Z")
                Var(c)
            }
            else -> throw FormulaException("Caractère inattendu « $c »")
        }
    }
}
