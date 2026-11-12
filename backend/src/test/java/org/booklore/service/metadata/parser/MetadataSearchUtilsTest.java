package org.booklore.service.metadata.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataSearchUtilsTest {

    @Test
    void authorVariantsDropPatronymicInBothNameOrders() {
        assertThat(MetadataSearchUtils.authorVariants("Галина Васильевна Герасимова"))
                .contains("Галина Васильевна Герасимова", "Галина Герасимова", "Герасимова");

        assertThat(MetadataSearchUtils.authorVariants("Герасимова Галина Васильевна"))
                .contains("Герасимова Галина Васильевна", "Герасимова Галина", "Герасимова");
    }

    @Test
    void isbnVariantsKeepRawAndNormalizedForms() {
        assertThat(MetadataSearchUtils.isbnVariants("978-5-9922-3373-5"))
                .containsExactly("978-5-9922-3373-5", "9785992233735");
        assertThat(MetadataSearchUtils.isbnVariants("9785992233735"))
                .containsExactly("9785992233735");
    }
}
