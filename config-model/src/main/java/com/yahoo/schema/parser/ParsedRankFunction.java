// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.schema.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * This class holds the extracted information after parsing a
 * "function" block in a rank-profile, using simple data structures as
 * far as possible.  Do not put advanced logic here!
 * @author arnej27959
 **/
public class ParsedRankFunction extends ParsedBlock {

    private boolean inline;
    private String expression;
    private final List<String> parameters = new ArrayList<>();

    public ParsedRankFunction(String name) {
        super(name, "function");
    }

    boolean getInline() { return this.inline; }
    String getExpression() { return this.expression; }
    List<String> getParameters() { return List.copyOf(parameters); }

    public void addParameter(String param) {
        verifyThat(! parameters.contains(param), "cannot have parameter", param, "twice");
        parameters.add(param);
    }

    public void setInline(boolean value) {
        this.inline = value;
    }

    public void setExpression(String value) {
        this.expression = value;
    }
}
