package io.github.magnolett.checkoutbff.bff.dto;

import io.github.magnolett.checkoutbff.bff.error.InvalidRequestException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public record PreCheckoutRequest(
        @NotEmpty @Size(max = 10) List<@NotNull @Valid ProductSelection> products) {

    public record ProductSelection(
            @Schema(example = "premium")
            @NotBlank @Size(max = 50) @Pattern(regexp = "\\s*[A-Za-z0-9-]+\\s*") String slug) {
    }

    /** Slugs trimmed and lower-cased. A repeated product is a contract violation: there is no quantity field. */
    public List<String> normalizedSlugs() {
        List<String> slugs = products.stream()
                .map(selection -> selection.slug().strip().toLowerCase(Locale.ROOT))
                .toList();
        if (new HashSet<>(slugs).size() != slugs.size()) {
            throw new InvalidRequestException("repeated product slug");
        }
        return slugs;
    }
}
