package edu.neu.safety.model;

/**
 * Marker interface for every message or payload that crosses the network
 * between Akka cluster nodes. The binding to jackson-cbor lives in
 * application.conf under akka.actor.serialization-bindings — any class that
 * implements this interface gets CBOR encoding for free.
 *
 * We went with CBOR over plain JSON because the payloads (sensor maps,
 * probability arrays, thermal stats) are numeric-heavy and CBOR is a bit
 * tighter on the wire. Java serialization is explicitly disabled in the
 * config so forgetting to implement this interface will fail fast in tests
 * rather than silently fall back.
 */
public interface CborSerializable {}
