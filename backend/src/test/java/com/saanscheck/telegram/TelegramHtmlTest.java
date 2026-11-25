package com.saanscheck.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Telegram HTML escaping, validity and length limits. */
class TelegramHtmlTest {

    @Test
    void escapesEverythingTelegramHtmlTreatsSpecially() {
        assertThat(TelegramHtml.esc("A&B <script>\"x\"")).isEqualTo("A&amp;B &lt;script&gt;&quot;x&quot;");
        assertThat(TelegramHtml.esc(null)).isEmpty();
        assertThat(TelegramHtml.isValid("<b>" + TelegramHtml.esc("Bad <i>station</i> & co") + "</b>")).isTrue();
    }

    @Test
    void validatorAcceptsOnlySupportedProperlyNestedTags() {
        assertThat(TelegramHtml.isValid("<b>AQI</b> <i>x</i> <code>1</code> <a href=\"https://x.y/?a=1&amp;b=2\">go</a>")).isTrue();
        assertThat(TelegramHtml.isValid("<b>open")).isFalse();
        assertThat(TelegramHtml.isValid("<b><i>x</b></i>")).isFalse();
        assertThat(TelegramHtml.isValid("<div>x</div>")).isFalse();
        assertThat(TelegramHtml.isValid("<b class=\"x\">x</b>")).isFalse();
        assertThat(TelegramHtml.isValid("a < b")).isFalse();
        assertThat(TelegramHtml.isValid("Tom & Jerry")).isFalse();
        assertThat(TelegramHtml.isValid("x".repeat(5000))).isFalse();
    }

    @Test
    void clipAndFitKeepMessagesWithinTelegramLimits() {
        String clipped = TelegramHtml.clip("word ".repeat(1000), 1200);
        assertThat(clipped.length()).isLessThanOrEqualTo(1201);
        assertThat(clipped).endsWith("…");
        String longMsg = ("<b>line</b> " + "y".repeat(90) + "\n").repeat(100);
        String fitted = TelegramHtml.fit(longMsg);
        assertThat(fitted.length()).isLessThanOrEqualTo(TelegramHtml.MAX_LENGTH);
        assertThat(TelegramHtml.isValid(fitted)).isTrue();                    // never cuts inside a tag
    }

    @Test
    void plainFallbackRemovesTagsAndDecodesEntities() {
        assertThat(TelegramHtml.toPlain("<b>AQI 210</b> &amp; <a href=\"https://v.d\">Open</a>"))
                .isEqualTo("AQI 210 & Open (https://v.d)");
    }
}
