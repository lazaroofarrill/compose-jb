package org.jetbrains.compose.web.css

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.ExperimentalComposeWebStyleApi
import org.jetbrains.compose.web.css.selectors.CSSSelector
import org.jetbrains.compose.web.dom.Style
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class CSSRulesHolderState : CSSRulesHolder {
    override var cssRules: CSSRuleDeclarationList by mutableStateOf(listOf())

    override fun add(cssRule: CSSRuleDeclaration) {
        @Suppress("SuspiciousCollectionReassignment")
        cssRules += cssRule
    }
}

/**
 * Represents a collection of the css style rules.
 * StyleSheet needs to be mounted.
 * @see [Style]
 *
 * Example:
 * ```
 * object AppStylesheet : StyleSheet() {
 *     val containerClass by style {
 *         padding(24.px)
 *     }
 * }
 * ```
 *
 * Usage Example:
 * ```
 *    Style(AppStylesheet) // this mounts the stylesheet
 *    Div(classes = listOf(AppStylesheet.containerClass),...)
 * ```
 */
open class StyleSheet(
    private val rulesHolder: CSSRulesHolder = CSSRulesHolderState(),
    val usePrefix: Boolean = true,
) : StyleSheetBuilder, CSSRulesHolder by rulesHolder {
    private val boundClasses = mutableMapOf<String, CSSRuleDeclarationList>()

    protected fun style(cssRule: CSSBuilder.() -> Unit) = CSSHolder(usePrefix, cssRule)

    /**
     * Example:
     * ```
     * object AppStyleSheet : StyleSheet() {
     *  val bounce by keyframes {
     *  from {
     *       property("transform", "translateX(50%)")
     *  }
     *
     *  to {
     *      property("transform", "translateX(-50%)")
     *  }
     * }
     *
     *   val myClass by style {
     *      animation(bounce) {
     *          duration(2.s)
     *          timingFunction(AnimationTimingFunction.EaseIn)
     *          direction(AnimationDirection.Alternate)
     *      }
     *    }
     *  }
     * ```
     */
    protected fun keyframes(cssKeyframes: CSSKeyframesBuilder.() -> Unit) = CSSKeyframesHolder(usePrefix, cssKeyframes)

    companion object {
        private var counter = 0
    }

    @Suppress("EqualsOrHashCode")
    internal class CSSSelfSelector(var selector: CSSSelector? = null) : CSSSelector() {
        override fun toString(): String = throw IllegalStateException("You can't concatenate `String + CSSSelector` which contains `self` or `root`. Use `selector(<your string>)` to convert `String` to `CSSSelector` for proper work. https://github.com/JetBrains/compose-jb/issues/1440")
        override fun asString(): String = selector?.asString() ?: throw IllegalStateException("You can't instantiate self")
        override fun equals(other: Any?): Boolean {
            return other is CSSSelfSelector
        }
    }

    // TODO: just proof of concept, do not use it
    @ExperimentalComposeWebStyleApi
    fun css(cssBuild: CSSBuilder.() -> Unit): String {
        val selfSelector = CSSSelfSelector()
        val (style, newCssRules) = buildCSS(selfSelector, selfSelector, cssBuild)
        val cssRule = cssRules.find {
            it is CSSStyleRuleDeclaration &&
                    it.selector is CSSSelector.CSSClass && it.style == style &&
                    (boundClasses[it.selector.className] ?: emptyList()) == newCssRules
        }.unsafeCast<CSSStyleRuleDeclaration?>()
        return if (cssRule != null) {
            cssRule.selector.unsafeCast<CSSSelector.CSSClass>().className
        } else {
            val classNameSelector = CSSSelector.CSSClass("auto-${counter++}")
            selfSelector.selector = classNameSelector
            add(classNameSelector, style)
            newCssRules.forEach { add(it) }
            boundClasses[classNameSelector.className] = newCssRules
            classNameSelector.className
        }
    }

    protected class CSSHolder(private val usePrefix: Boolean, private val cssBuilder: CSSBuilder.() -> Unit) {
        operator fun provideDelegate(
            sheet: StyleSheet,
            property: KProperty<*>
        ): ReadOnlyProperty<Any?, String> {
            val sheetName = if (usePrefix) "${sheet::class.simpleName}-" else ""
            val selector = CSSSelector.CSSClass("$sheetName${property.name}")
            val (properties, rules) = buildCSS(selector, selector, cssBuilder)
            sheet.add(selector, properties)
            rules.forEach { sheet.add(it) }

            return ReadOnlyProperty { _, _ ->
                selector.className
            }
        }
    }

    /**
     * See [keyframes]
     */
    protected class CSSKeyframesHolder(
        private val usePrefix: Boolean,
        private val keyframesBuilder: CSSKeyframesBuilder.() -> Unit
    ) {
        operator fun provideDelegate(
            sheet: StyleSheet,
            property: KProperty<*>
        ): ReadOnlyProperty<Any?, CSSNamedKeyframes> {
            val sheetName = if (usePrefix) "${sheet::class.simpleName}-" else ""
            val keyframesName = "$sheetName${property.name}"
            val rule = buildKeyframes(keyframesName, keyframesBuilder)
            sheet.add(rule)

            return ReadOnlyProperty { _, _ ->
                rule
            }
        }
    }

    override fun buildRules(rulesBuild: GenericStyleSheetBuilder<CSSStyleRuleBuilder>.() -> Unit) =
        StyleSheet().apply(rulesBuild).cssRules
}

internal fun buildCSS(
    thisClass: CSSSelector,
    thisContext: CSSSelector,
    cssRule: CSSBuilder.() -> Unit
): Pair<StyleHolder, CSSRuleDeclarationList> {
    val styleSheet = StyleSheetBuilderImpl()
    // workaround because of problems with plus operator overloading
    val root = if (thisClass is StyleSheet.CSSSelfSelector) thisClass else StyleSheet.CSSSelfSelector(thisClass)
    // workaround because of problems with plus operator overloading
    val self = if (thisContext is StyleSheet.CSSSelfSelector) thisContext else StyleSheet.CSSSelfSelector(thisContext)

    val builder = CSSBuilderImpl(root, self, styleSheet)
    builder.cssRule()
    return builder to styleSheet.cssRules
}

@Composable
inline fun Style(
    styleSheet: CSSRulesHolder
) {
    Style(cssRules = styleSheet.cssRules)
}
