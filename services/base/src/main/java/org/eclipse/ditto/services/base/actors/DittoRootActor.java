/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.base.actors;

import static akka.http.javadsl.server.Directives.logRequest;
import static akka.http.javadsl.server.Directives.logResult;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.util.NoSuchElementException;

import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.services.base.config.http.HttpConfig;
import org.eclipse.ditto.services.utils.akka.logging.DittoDiagnosticLoggingAdapter;
import org.eclipse.ditto.services.utils.akka.logging.DittoLoggerFactory;
import org.eclipse.ditto.services.utils.cluster.ClusterStatusSupplier;
import org.eclipse.ditto.services.utils.config.LocalHostAddressSupplier;
import org.eclipse.ditto.services.utils.health.routes.StatusRoute;

import akka.actor.AbstractActor;
import akka.actor.ActorInitializationException;
import akka.actor.ActorKilledException;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.InvalidActorNameException;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.Status;
import akka.actor.SupervisorStrategy;
import akka.cluster.Cluster;
import akka.http.javadsl.Http;
import akka.http.javadsl.server.Route;
import akka.japi.pf.DeciderBuilder;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.AskTimeoutException;
import scala.PartialFunction;

/**
 * Abstract implementation of a root actor of a ditto service.
 */
public abstract class DittoRootActor extends AbstractActor {

    private final DittoDiagnosticLoggingAdapter log = DittoLoggerFactory.getDiagnosticLoggingAdapter(this);
    private final PartialFunction<Throwable, SupervisorStrategy.Directive> supervisionDecider = DeciderBuilder
            .match(NullPointerException.class, e -> {
                log.error(e, "NullPointer in child actor: {}", e.getMessage());
                return restartChild();
            }).match(IllegalArgumentException.class, e -> {
                log.warning("Illegal Argument in child actor: {}", e.getMessage());

                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);

                log.warning("Illegal Argument in child actor: {}", sw.toString());
                return (SupervisorStrategy.Directive) SupervisorStrategy.resume();
            }).match(IllegalStateException.class, e -> {
                log.warning("Illegal State in child actor: {}", e.getMessage());
                return (SupervisorStrategy.Directive) SupervisorStrategy.resume();
            }).match(IndexOutOfBoundsException.class, e -> {
                log.warning("IndexOutOfBounds in child actor: {}", e.getMessage());
                return (SupervisorStrategy.Directive) SupervisorStrategy.resume();
            }).match(NoSuchElementException.class, e -> {
                log.warning("NoSuchElement in child actor: {}", e.getMessage());
                return (SupervisorStrategy.Directive) SupervisorStrategy.resume();
            }).match(AskTimeoutException.class, e -> {
                log.warning("AskTimeoutException in child actor: {}", e.getMessage());
                return (SupervisorStrategy.Directive) SupervisorStrategy.resume();
            }).match(ConnectException.class, e -> {
                log.warning("ConnectException in child actor: {}", e.getMessage());
                return restartChild();
            }).match(InvalidActorNameException.class, e -> {
                log.warning("InvalidActorNameException in child actor: {}", e.getMessage());
                return (SupervisorStrategy.Directive) SupervisorStrategy.resume();
            }).match(ActorInitializationException.class, e -> {
                log.error(e, "ActorInitializationException in child actor: {}", e.getMessage());
                return (SupervisorStrategy.Directive) SupervisorStrategy.stop();
            }).match(ActorKilledException.class, e -> {
                log.error(e, "ActorKilledException in child actor: {}", e.message());
                return restartChild();
            }).match(DittoRuntimeException.class, e -> {
                log.error(e,
                        "DittoRuntimeException '{}' should not be escalated to ConnectivityRootActor. Simply resuming Actor.",
                        e.getErrorCode());
                return (SupervisorStrategy.Directive) SupervisorStrategy.resume();
            }).match(Throwable.class, e -> {
                log.error(e, "Escalating above root actor!");
                return (SupervisorStrategy.Directive) SupervisorStrategy.escalate();
            }).matchAny(e -> {
                log.error("Unknown message:'{}'! Escalating above root actor!", e);
                return (SupervisorStrategy.Directive) SupervisorStrategy.escalate();
            }).build();

    private final SupervisorStrategy strategy = new OneForOneStrategy(true, getSupervisionDecider());

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(StartChildActor.class, this::startChildActor)
                .match(Status.Failure.class, f -> log.error(f.cause(), "Got failure <{}>!", f))
                .matchAny(m -> {
                    log.warning("Unknown message <{}>.", m);
                    unhandled(m);
                })
                .build();
    }

    @Override
    public SupervisorStrategy supervisorStrategy() {
        return strategy;
    }

    /**
     * Gets the partial function that handles a throwable and returns a SupervisorStrategy.Directive. This PF will be
     * used for the supervision strategy of this actor.
     *
     * @return the partial function.
     */
    protected PartialFunction<Throwable, SupervisorStrategy.Directive> getSupervisionDecider() {
        return supervisionDecider;
    }

    /**
     * Starts an actor in the context of this actor.
     *
     * @param actorName the name of the actor to start.
     * @param props the props of the actor to start.
     * @return the ref of the started actor.
     */
    protected ActorRef startChildActor(final String actorName, final Props props) {
        log.info("Starting child actor <{}>.", actorName);
        return getContext().actorOf(props, actorName);
    }

    /**
     * Returns the restart supervisor strategy.
     *
     * @return the restart supervisor strategy.
     */
    protected SupervisorStrategy.Directive restartChild() {
        log.info("Restarting child ...");
        return (SupervisorStrategy.Directive) SupervisorStrategy.restart();
    }

    private void startChildActor(final StartChildActor startChildActor) {
        startChildActor(startChildActor.getActorName(), startChildActor.getProps());
    }

    protected void bindHttpStatusRoute(final HttpConfig httpConfig, final ActorRef healthCheckingActor) {

        String hostname = httpConfig.getHostname();
        if (hostname.isEmpty()) {
            hostname = LocalHostAddressSupplier.getInstance().get();
            log.info("No explicit hostname configured, using HTTP hostname: {}", hostname);
        }

        final ActorSystem system = getContext().getSystem();

        Http.get(system)
                .newServerAt(hostname, httpConfig.getPort())
                .bindFlow(createStatusRoute(system, healthCheckingActor).flow(system))
                .thenAccept(theBinding -> {
                    theBinding.addToCoordinatedShutdown(httpConfig.getCoordinatedShutdownTimeout(), system);
                    log.info("Created new server binding for the status route.");
                })
                .exceptionally(failure -> {
                    log.error(failure,
                            "Could not create the server binding for the status route because of: <{}>",
                            failure.getMessage());
                    log.error("Terminating the actor system");
                    system.terminate();
                    return null;
                });
    }

    private static Route createStatusRoute(final ActorSystem actorSystem, final ActorRef healthCheckingActor) {
        final StatusRoute statusRoute = new StatusRoute(new ClusterStatusSupplier(Cluster.get(actorSystem)),
                healthCheckingActor, actorSystem);

        return logRequest("http-request", () -> logResult("http-response", statusRoute::buildStatusRoute));
    }


}
