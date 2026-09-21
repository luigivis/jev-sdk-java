package com.luigivismara.jev;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A model or alias available to the authenticated account, as returned by
 * {@link JevClient#models()}.
 *
 * <pre>{@code
 * for (ModelInfo model : jev.models()) {
 *     System.out.println(model.name() + ": " + model.description());
 * }
 * // jev-latest: The latest iteration of TypeSafe's System One Model: Jev
 * // jev-preview: A preview version of `jev-latest`: should be better in most ways
 * }</pre>
 *
 * @param name        name to pass as the request model
 * @param description human-readable description
 * @param releaseDate release date, as sent by the API (kept as a string: the published schema
 *                    says {@code YYYY-MM-DD} but the live API returns a full ISO timestamp)
 */
public record ModelInfo(
        String name,
        String description,
        @JsonProperty("release_date") String releaseDate) {
}
