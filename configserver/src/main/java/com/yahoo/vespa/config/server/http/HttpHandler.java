// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http;

import com.yahoo.config.provision.ApplicationLockException;
import com.yahoo.config.provision.CertificateNotReadyException;
import com.yahoo.config.provision.NodeAllocationException;
import com.yahoo.config.provision.ParentHostUnavailableException;
import com.yahoo.config.provision.QuotaExceededException;
import com.yahoo.config.provision.RequestedNodesMissingException;
import com.yahoo.config.provision.exception.ActivationConflictException;
import com.yahoo.config.provision.exception.LoadBalancerServiceException;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.vespa.config.server.application.ConfigNotConvergedException;
import com.yahoo.container.jdisc.ThreadedHttpRequestHandler;
import com.yahoo.yolean.Exceptions;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.logging.Level;

/**
 * Super class for http handlers, that takes care of checking valid
 * methods for a request. Handlers should subclass this method and
 * implement the handleMETHOD methods that it supports.
 *
 * @author hmusum
 */
public class HttpHandler extends ThreadedHttpRequestHandler {

    public HttpHandler(HttpHandler.Context ctx) {
        super(ctx);
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        log.log(Level.FINE, () -> request.getMethod() + " " + request.getUri().toString());
        try {
            return switch (request.getMethod()) {
                case POST -> handlePOST(request);
                case GET -> handleGET(request);
                case PUT -> handlePUT(request);
                case DELETE -> handleDELETE(request);
                default -> createErrorResponse(request.getMethod());
            };
        } catch (NotFoundException | com.yahoo.vespa.config.server.NotFoundException e) {
            return HttpErrorResponse.notFoundError(getMessage(e, request));
        } catch (ActivationConflictException e) {
            return HttpErrorResponse.conflictWhenActivating(getMessage(e, request));
        } catch (InvalidApplicationException e) {
            return HttpErrorResponse.invalidApplicationPackage(getMessage(e, request));
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return HttpErrorResponse.badRequest(getMessage(e, request));
        } catch (NodeAllocationException e) {
            return e.retryable() ? HttpErrorResponse.nodeAllocationFailure(getMessage(e, request))
                                 : HttpErrorResponse.invalidApplicationPackage(getMessage(e, request));
        } catch (InternalServerException e) {
            return HttpErrorResponse.internalServerError(getMessage(e, request));
        } catch (UnknownVespaVersionException e) {
            return HttpErrorResponse.unknownVespaVersion(getMessage(e, request));
        } catch (RequestTimeoutException e) {
            return HttpErrorResponse.requestTimeout(getMessage(e, request));
        } catch (ApplicationLockException e) {
            return HttpErrorResponse.applicationLockFailure(getMessage(e, request));
        } catch (ParentHostUnavailableException e) {
            return HttpErrorResponse.parentHostNotReady(getMessage(e, request));
        } catch (RequestedNodesMissingException e) {
            return HttpErrorResponse.requestedNodesMissing(getMessage(e, request));
        } catch (CertificateNotReadyException e) {
            return HttpErrorResponse.certificateNotReady(getMessage(e, request));
        } catch (ConfigNotConvergedException e) {
            return HttpErrorResponse.configNotConverged(getMessage(e, request));
        } catch (LoadBalancerServiceException e) {
            return HttpErrorResponse.loadBalancerNotReady(getMessage(e, request));
        } catch (ReindexingStatusException e) {
            return HttpErrorResponse.reindexingStatusUnavailable(getMessage(e, request));
        } catch (PreconditionFailedException e) {
            return HttpErrorResponse.preconditionFailed(getMessage(e, request));
        } catch (QuotaExceededException e) {
            return HttpErrorResponse.quotaExceeded(getMessage(e, request));
        } catch (Exception e) {
            log.log(Level.WARNING, "Unexpected exception handling a config server request", e);
            return HttpErrorResponse.internalServerError(getMessage(e, request));
        }
    }

    protected static Duration getRequestTimeout(HttpRequest request, Duration defaultTimeout) {
        if ( ! request.hasProperty("timeout")) {
            return defaultTimeout;
        }
        try {
            return Duration.ofMillis((long) (Double.parseDouble(request.getProperty("timeout")) * 1000));
        } catch (Exception e) {
            return defaultTimeout;
        }
    }

    private String getMessage(Exception e, HttpRequest request) {
        if (request.getBooleanProperty("debug")) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            return sw.toString();
        } else {
            return Exceptions.toMessageString(e);
        }
    }

    /**
     * Default implementation of handler for GET requests. Returns an error response.
     * Override this method to handle GET requests.
     *
     * @param request a {@link HttpRequest}
     * @return an error response with response code 405
     */
    protected HttpResponse handleGET(HttpRequest request) {
        return createErrorResponse(request.getMethod());
    }

    /**
     * Default implementation of handler for POST requests. Returns an error response.
     * Override this method to handle POST requests.
     *
     * @param request a {@link HttpRequest}
     * @return an error response with response code 405
     */
    protected HttpResponse handlePOST(HttpRequest request) {
        return createErrorResponse(request.getMethod());
    }

    /**
     * Default implementation of handler for PUT requests. Returns an error response.
     * Override this method to handle POST requests.
     *
     * @param request a {@link HttpRequest}
     * @return an error response with response code 405
     */
    protected HttpResponse handlePUT(HttpRequest request) {
        return createErrorResponse(request.getMethod());
    }

    /**
     * Default implementation of handler for DELETE requests. Returns an error response.
     * Override this method to handle DELETE requests.
     *
     * @param request a {@link HttpRequest}
     * @return an error response with response code 405
     */
    protected HttpResponse handleDELETE(HttpRequest request) {
        return createErrorResponse(request.getMethod());
    }

    /**
     * Creates error response when method is not handled
     *
     * @return an error response with response code 405
     */
    private HttpResponse createErrorResponse(com.yahoo.jdisc.http.HttpRequest.Method method) {
        return HttpErrorResponse.methodNotAllowed("Method '" + method + "' is not supported");
    }
}
