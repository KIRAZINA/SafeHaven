package com.safehaven.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainControllerPaginationTest {

    @Test
    void totalPages_RoundsUpAndHandlesEmptyResults() {
        assertEquals(0, MainController.totalPages(0, 25));
        assertEquals(1, MainController.totalPages(1, 25));
        assertEquals(1, MainController.totalPages(25, 25));
        assertEquals(2, MainController.totalPages(26, 25));
        assertEquals(5, MainController.totalPages(101, 25));
        assertEquals(0, MainController.totalPages(10, 0), "A non-positive size yields no pages");
    }

    @Test
    void clampPage_KeepsRequestedPageWithinBounds() {
        assertEquals(0, MainController.clampPage(0, 42, 25));
        assertEquals(1, MainController.clampPage(1, 42, 25));
        assertEquals(1, MainController.clampPage(9, 42, 25), "Out-of-range pages clamp to the last page");
        assertEquals(0, MainController.clampPage(3, 0, 25), "Empty results always clamp to page zero");
        assertEquals(0, MainController.clampPage(-1, 42, 25), "Negative pages clamp to the first page");
    }

    @Test
    void pageOffset_MultipliesPageBySize() {
        assertEquals(0, MainController.pageOffset(0, 25));
        assertEquals(25, MainController.pageOffset(1, 25));
        assertEquals(100, MainController.pageOffset(4, 25));
        assertEquals(0, MainController.pageOffset(-1, 25));
    }

    @Test
    void pageInfoText_MatchesRequiredFormat() {
        assertEquals("Page 1 of 5 (Total: 42 items)",
                MainController.pageInfoText(0, 5, 42));
        assertEquals("Page 5 of 5 (Total: 42 items)",
                MainController.pageInfoText(4, 5, 42));
        assertEquals("Page 1 of 1 (Total: 0 items)",
                MainController.pageInfoText(0, 0, 0));
    }
}
