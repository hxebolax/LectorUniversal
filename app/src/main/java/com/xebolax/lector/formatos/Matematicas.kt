package com.xebolax.lector.formatos

import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Fórmulas MathML dichas con palabras, para que la voz y TalkBack las lean
 * con sentido («x elevado a 2 más 1», «fracción, a entre b, fin de la
 * fracción») en lugar de juntar los símbolos («x2+1»). Cubre lo habitual en
 * libros de texto: potencias, subíndices, fracciones, raíces, sumatorios,
 * integrales, paréntesis, operadores y tablas (matrices).
 */
object Matematicas {

	private class Palabras(
		val mas: String, val menos: String, val por: String, val entre: String, val igual: String,
		val elevado: String, val cuadrado: String, val cubo: String, val finExponente: String,
		val sub: String, val fraccion: String, val finFraccion: String,
		val raiz: String, val raizIndice: String, val finRaiz: String,
		val desde: String, val hasta: String, val abre: String, val cierra: String, val fila: String,
		val simbolos: Map<String, String>,
	)

	private val ES = Palabras(
		"más", "menos", "por", "entre", "igual a", "elevado a", "al cuadrado", "al cubo", "fin del exponente",
		"sub", "fracción,", "fin de la fracción", "raíz cuadrada de", "raíz de índice %s de", "fin de la raíz",
		"desde", "hasta", "abre paréntesis", "cierra paréntesis", "fila",
		mapOf(
			"<" to "menor que", ">" to "mayor que", "≤" to "menor o igual que", "≥" to "mayor o igual que", "≠" to "distinto de",
			"≈" to "aproximadamente igual a", "±" to "más menos", "∓" to "menos más", "∞" to "infinito", "→" to "tiende a",
			"∑" to "suma", "∏" to "producto", "∫" to "integral", "∂" to "derivada parcial de", "∇" to "nabla", "∈" to "pertenece a",
			"∉" to "no pertenece a", "⊂" to "contenido en", "∪" to "unión", "∩" to "intersección", "∀" to "para todo", "∃" to "existe",
			"⇒" to "implica", "⇔" to "si y solo si", "√" to "raíz de", "%" to "por ciento", "°" to "grados", "!" to "factorial",
			"π" to "pi", "α" to "alfa", "β" to "beta", "γ" to "gamma", "δ" to "delta", "Δ" to "delta mayúscula", "ε" to "épsilon",
			"θ" to "theta", "λ" to "lambda", "μ" to "mu", "σ" to "sigma", "Σ" to "sigma mayúscula", "φ" to "fi", "ω" to "omega",
			"Ω" to "omega mayúscula", "[" to "abre corchete", "]" to "cierra corchete", "{" to "abre llave", "}" to "cierra llave",
			"|" to "valor absoluto", "⋅" to "por", "·" to "por", "×" to "por", "*" to "por", "÷" to "entre", "/" to "entre",
		),
	)

	private val EN = Palabras(
		"plus", "minus", "times", "over", "equals", "to the power of", "squared", "cubed", "end exponent",
		"sub", "fraction,", "end fraction", "square root of", "root of index %s of", "end root",
		"from", "to", "open parenthesis", "close parenthesis", "row",
		mapOf(
			"<" to "less than", ">" to "greater than", "≤" to "less than or equal to", "≥" to "greater than or equal to", "≠" to "not equal to",
			"≈" to "approximately equal to", "±" to "plus or minus", "∓" to "minus or plus", "∞" to "infinity", "→" to "tends to",
			"∑" to "sum", "∏" to "product", "∫" to "integral", "∂" to "partial derivative of", "∇" to "nabla", "∈" to "is in",
			"∉" to "is not in", "⊂" to "subset of", "∪" to "union", "∩" to "intersection", "∀" to "for all", "∃" to "there exists",
			"⇒" to "implies", "⇔" to "if and only if", "√" to "root of", "%" to "percent", "°" to "degrees", "!" to "factorial",
			"π" to "pi", "α" to "alpha", "β" to "beta", "γ" to "gamma", "δ" to "delta", "Δ" to "capital delta", "ε" to "epsilon",
			"θ" to "theta", "λ" to "lambda", "μ" to "mu", "σ" to "sigma", "Σ" to "capital sigma", "φ" to "phi", "ω" to "omega",
			"Ω" to "capital omega", "[" to "open bracket", "]" to "close bracket", "{" to "open brace", "}" to "close brace",
			"|" to "absolute value", "⋅" to "times", "·" to "times", "×" to "times", "*" to "times", "÷" to "divided by", "/" to "over",
		),
	)

	/** La fórmula dicha con palabras (vacío si no es MathML de presentación). */
	fun hablar(math: Element, idioma: String = Locale.getDefault().language): String {
		val p = if (idioma.startsWith("es")) ES else EN
		return decir(math, p).replace(Regex("\\s+"), " ").replace(" ,", ",").trim()
	}

	/** ¿Es un solo elemento sencillo (para no decir «fin de...» en «x al cuadrado»)? */
	private fun simple(e: Element?): Boolean = e != null && (e.tagName() in setOf("mi", "mn", "mo", "mtext") ||
		(e.tagName() == "mrow" && e.children().size == 1 && simple(e.child(0))))

	private fun decir(e: Element?, p: Palabras): String {
		e ?: return ""
		val hijos = e.children()
		fun h(i: Int) = hijos.getOrNull(i)
		return when (e.tagName().substringAfter(':')) {
			"math", "mrow", "mstyle", "mpadded", "mphantom", "menclose", "mlabeledtr", "mtd" -> hijos.joinToString(" ") { decir(it, p) }
			"semantics" -> decir(h(0), p)
			"annotation", "annotation-xml", "none", "mprescripts" -> ""
			"mi", "mn", "mtext", "ms" -> simbolo(e.text().trim(), p)
			"mo" -> operador(e.text().trim(), p)
			"mspace" -> " "
			"msup" -> potencia(h(0), h(1), p)
			"msub" -> "${decir(h(0), p)} ${p.sub} ${decir(h(1), p)}"
			"msubsup" -> "${decir(h(0), p)} ${p.sub} ${decir(h(1), p)} " + potencia(null, h(2), p)
			"mfrac" -> if (simple(h(0)) && simple(h(1))) "${decir(h(0), p)} ${p.entre} ${decir(h(1), p)}"
				else "${p.fraccion} ${decir(h(0), p)} ${p.entre} ${decir(h(1), p)}, ${p.finFraccion},"
			"msqrt" -> if (hijos.size == 1 && simple(h(0))) "${p.raiz} ${decir(h(0), p)}" else "${p.raiz} ${hijos.joinToString(" ") { decir(it, p) }}, ${p.finRaiz},"
			"mroot" -> "${p.raizIndice.format(decir(h(1), p))} ${decir(h(0), p)}, ${p.finRaiz},"
			"munderover" -> "${decir(h(0), p)} ${p.desde} ${decir(h(1), p)} ${p.hasta} ${decir(h(2), p)}"
			"munder" -> "${decir(h(0), p)} ${p.desde} ${decir(h(1), p)}"
			"mover" -> "${decir(h(0), p)} ${decir(h(1), p)}"
			"mfenced" -> {
				val abre = e.attr("open").ifEmpty { "(" }
				val cierra = e.attr("close").ifEmpty { ")" }
				"${operador(abre, p)} ${hijos.joinToString(", ") { decir(it, p) }} ${operador(cierra, p)}"
			}
			"mtable" -> hijos.mapIndexed { i, fila -> "${p.fila} ${i + 1}: ${fila.children().joinToString(", ") { decir(it, p) }}" }.joinToString("; ")
			"mtr" -> hijos.joinToString(", ") { decir(it, p) }
			else -> hijos.joinToString(" ") { decir(it, p) }.ifBlank { e.ownText() }
		}
	}

	private fun potencia(base: Element?, exponente: Element?, p: Palabras): String {
		val b = base?.let { decir(it, p) }.orEmpty()
		val ex = exponente?.text()?.trim().orEmpty()
		return when {
			ex == "2" -> "$b ${p.cuadrado}"
			ex == "3" -> "$b ${p.cubo}"
			simple(exponente) -> "$b ${p.elevado} ${decir(exponente, p)}"
			else -> "$b ${p.elevado} ${decir(exponente, p)}, ${p.finExponente},"
		}
	}

	private fun operador(o: String, p: Palabras): String = when (o) {
		"+" -> p.mas
		"-", "−" -> p.menos
		"=" -> p.igual
		"(" -> p.abre
		")" -> p.cierra
		"", "⁡", "⁢", "⁣" -> "" // aplicación de función y producto invisibles
		else -> p.simbolos[o] ?: o
	}

	private fun simbolo(t: String, p: Palabras): String = if (t.length == 1) p.simbolos[t] ?: t else t
}
