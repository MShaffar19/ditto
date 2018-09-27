/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 * SPDX-License-Identifier: EPL-2.0
 *
 */

package org.eclipse.ditto.model.connectivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mutabilitydetector.unittesting.AllowedReason.provided;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.auth.AuthorizationModelFactory;
import org.junit.Test;

import nl.jqno.equalsverifier.EqualsVerifier;

public final class ImmutableMqttSourceTest {

    private static final AuthorizationContext ctx = AuthorizationModelFactory.newAuthContext(
            AuthorizationModelFactory.newAuthSubject("eclipse"), AuthorizationModelFactory.newAuthSubject("ditto"));

    private static final String AMQP_SOURCE1 = "amqp/source1";
    private static final String FILTER = "topic/{{ thing.id }}";

    private static final Enforcement enforcement = ConnectivityModelFactory.newEnforcement("{{ topic }}", FILTER);

    private static final MqttSource MQTT_SOURCE = ConnectivityModelFactory
            .newMqttSourceBuilder()
            .authorizationContext(ctx)
            .address(AMQP_SOURCE1)
            .enforcement(enforcement)
            .consumerCount(2)
            .index(0)
            .qos(1)
            .build();

    private static final JsonObject SOURCE_JSON = JsonObject
            .newBuilder()
            .set(Source.JsonFields.ADDRESSES, JsonFactory.newArrayBuilder().add(AMQP_SOURCE1).build())
            .set(Source.JsonFields.CONSUMER_COUNT, 2)
            .set(MqttSource.JsonFields.QOS, 1)
            .set(Source.JsonFields.AUTHORIZATION_CONTEXT, JsonFactory.newArrayBuilder().add("eclipse", "ditto").build())
            .set(Source.JsonFields.ENFORCEMENT, JsonFactory.newObjectBuilder()
                    .set(Enforcement.JsonFields.INPUT, "{{ topic }}")
                    .set(Enforcement.JsonFields.MATCHERS, JsonFactory.newArrayBuilder().add(FILTER).build())
                    .build())
            .build();

    @Test
    public void testHashCodeAndEquals() {
        EqualsVerifier.forClass(ImmutableMqttSource.class)
                .usingGetClass()
                .verify();
    }

    @Test
    public void assertImmutability() {
        assertInstancesOf(ImmutableMqttSource.class, areImmutable(),
                provided(AuthorizationContext.class).isAlsoImmutable());
    }

    @Test
    public void toJsonReturnsExpected() {
        final JsonObject actual = MQTT_SOURCE.toJson();
        assertThat(actual).isEqualTo(SOURCE_JSON);
    }

    @Test
    public void fromJsonReturnsExpected() {
        final Source actual = ImmutableMqttSource.fromJson(SOURCE_JSON, 0);
        assertThat(actual).isEqualTo(MQTT_SOURCE);
    }

}
