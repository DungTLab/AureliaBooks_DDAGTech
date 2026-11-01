package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.util.ProductImageUrls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ProductImageReferencePolicyTest {
    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String URL = "/products/images/" + ID;
    private final ProductImageStorage storage = mock(ProductImageStorage.class);
    private final ProductImageReferencePolicy policy = new ProductImageReferencePolicy(
            new StaticListableBeanFactory(java.util.Map.of("images", storage)).getBeanProvider(ProductImageStorage.class));

    @Test void newInternalReferenceMustExist() {
        policy.validateForSave(URL, null);
        verify(storage).read(ID);
    }
    @Test void missingObjectIsInvalidInput() {
        when(storage.read(ID)).thenThrow(new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> policy.validateForSave(URL, null)).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));
    }
    @Test void outageDoesNotAllowSavingNewReference() {
        when(storage.read(ID)).thenThrow(new IllegalStateException("outage"));
        assertThatThrownBy(() -> policy.validateForSave(URL, null)).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_IMAGE_UNAVAILABLE));
    }
    @Test void disabledStorageBlocksNewButPreservesUnchangedInternalReference() {
        var disabled = new ProductImageReferencePolicy(new StaticListableBeanFactory().getBeanProvider(ProductImageStorage.class));
        assertThatThrownBy(() -> disabled.validateForSave(URL, null)).isInstanceOf(AppException.class);
        disabled.validateForSave(URL, URL);
    }
    @ParameterizedTest @ValueSource(strings = {"https://example.com/cover.jpg?x=1&y=2", "http://example.com/old.jpg", "", "https://example.com/a%20b.png"})
    void externalOrEmptyDoesNotContactStorage(String value) {
        policy.validateForSave(value, URL);
        verifyNoInteractions(storage);
    }
    @ParameterizedTest @ValueSource(strings = {"javascript:alert(1)", "data:image/png;base64,abc", "//example.com/a", "/other/path.png", "products/a.png", "/products/images/../secret", "/products/images/550e8400-e29b-41d4-a716-446655440000?x=1", "https://user:pass@example.com/a", "https://account.r2.cloudflarestorage.com/products/x.png", "https://example.com/\\evil"})
    void invalidReferencesAreRejectedWithoutStorage(String value) {
        assertThatThrownBy(() -> policy.validateForSave(value, null)).isInstanceOf(AppException.class);
        verifyNoInteractions(storage);
    }
    @Test void unchangedLegacyLinksAreRetainedWithoutLookup() {
        policy.validateForSave("/legacy/images/book.jpg", "/legacy/images/book.jpg");
        policy.validateForSave(URL, URL);
        verifyNoInteractions(storage);
    }
    @Test void objectKeyMustBeStrictlyCanonical() {
        assertThat(ProductImageUrls.fromObjectKey("products/" + ID + ".png")).isEqualTo(URL);
        assertThatThrownBy(() -> ProductImageUrls.fromObjectKey("products/../../secret.png")).isInstanceOf(IllegalStateException.class);
        assertThat(ProductImageUrls.normalize("  " + URL + "  ")).isEqualTo(URL);
    }
}
