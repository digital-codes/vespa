// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.indexinglanguage;

import com.yahoo.javacc.FastCharStream;
import com.yahoo.vespa.indexinglanguage.expressions.Expression;
import com.yahoo.vespa.indexinglanguage.expressions.ScriptExpression;
import com.yahoo.vespa.indexinglanguage.expressions.StatementExpression;
import com.yahoo.vespa.indexinglanguage.parser.CharStream;
import com.yahoo.vespa.indexinglanguage.parser.IndexingParser;
import com.yahoo.vespa.indexinglanguage.parser.ParseException;
import com.yahoo.vespa.indexinglanguage.parser.TokenMgrException;

/**
 * @author Simon Thoresen Hult
 */
public final class ScriptParser {

    public static Expression parseExpression(ScriptParserContext config) throws ParseException {
        return parse(config, parser -> parser.root());
    }

    public static ScriptExpression parseScript(ScriptParserContext config) throws ParseException {
        return parse(config, parser -> parser.script());
    }

    public static StatementExpression parseStatement(ScriptParserContext config) throws ParseException {
        return parse(config, parser -> {
            try {
                return parser.statement();
            }
            catch (TokenMgrException e) {
                throw new ParseException(e.getMessage());
            }
        });
    }

    private interface ParserMethod<T extends Expression> {

        T call(IndexingParser parser) throws ParseException;
    }

    private static <T extends Expression> T parse(ScriptParserContext context, ParserMethod<T> method)
            throws ParseException {
        CharStream input = context.getInputStream();
        IndexingParser parser = new IndexingParser(input);
        parser.setDefaultFieldName(context.getDefaultFieldName());
        parser.setLinguistics(context.getLinguistcs());
        parser.setChunkers(context.getChunkers());
        parser.setEmbedders(context.getEmbedders());
        parser.setGenerators(context.getGenerators());
        
        try {
            return method.call(parser);
        } catch (ParseException e) {
            if (!(input instanceof FastCharStream)) {
                throw e;
            }
            throw new ParseException(((FastCharStream)input).formatException(e.getMessage()));
        } finally {
            if (parser.token != null && parser.token.next != null) {
                input.backup(parser.token.next.image.length());
            }
        }
    }

}
