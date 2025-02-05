/*
 * Copyright 2017-2020, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.oauth.jsonpath;

/**
 * The exception signalling a syntactic or semantic error during JsonPathQuery or JsonPathFilterQuery parsing
 */
public class JsonPathQueryException extends RuntimeException {

    public JsonPathQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
