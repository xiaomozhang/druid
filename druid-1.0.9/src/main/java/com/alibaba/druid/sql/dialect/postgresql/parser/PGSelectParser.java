/*
 * Copyright 1999-2011 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql.dialect.postgresql.parser;

import java.util.List;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLSetQuantifier;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.dialect.postgresql.ast.expr.PGParameter;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGFunctionTableSource;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock.IntoOption;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLExprParser;
import com.alibaba.druid.sql.parser.SQLSelectParser;
import com.alibaba.druid.sql.parser.Token;

public class PGSelectParser extends SQLSelectParser {

    public PGSelectParser(SQLExprParser exprParser){
        super(exprParser);
    }

    public PGSelectParser(String sql){
        this(new PGExprParser(sql));
    }

    protected SQLExprParser createExprParser() {
        return new PGExprParser(lexer);
    }

    @Override
    public SQLSelectQuery query() {
        PGSelectQueryBlock queryBlock = new PGSelectQueryBlock();

        if (lexer.token() == Token.SELECT) {
            lexer.nextToken();
            
            if (lexer.token() == Token.COMMENT) {
                lexer.nextToken();
            }

            if (lexer.token() == Token.DISTINCT) {
                queryBlock.setDistionOption(SQLSetQuantifier.DISTINCT);
                lexer.nextToken();

                if (lexer.token() == Token.ON) {
                    lexer.nextToken();

                    for (;;) {
                        SQLExpr expr = this.createExprParser().expr();
                        queryBlock.getDistinctOn().add(expr);
                        if (lexer.token() == Token.COMMA) {
                            lexer.nextToken();
                            continue;
                        } else {
                            break;
                        }
                    }
                }
            } else if (lexer.token() == Token.ALL) {
                queryBlock.setDistionOption(SQLSetQuantifier.ALL);
                lexer.nextToken();
            }

            parseSelectList(queryBlock);

            if (lexer.token() == Token.INTO) {
                lexer.nextToken();

                if (lexer.token() == Token.TEMPORARY) {
                    lexer.nextToken();
                    queryBlock.setIntoOption(IntoOption.TEMPORARY);
                } else if (lexer.token() == Token.TEMP) {
                    lexer.nextToken();
                    queryBlock.setIntoOption(IntoOption.TEMP);
                } else if (lexer.token() == Token.UNLOGGED) {
                    lexer.nextToken();
                    queryBlock.setIntoOption(IntoOption.UNLOGGED);
                }

                if (lexer.token() == Token.TABLE) {
                    lexer.nextToken();
                }

                SQLExpr name = this.createExprParser().name();

                queryBlock.setInto(new SQLExprTableSource(name));
            }
        }

        parseFrom(queryBlock);

        parseWhere(queryBlock);

        parseGroupBy(queryBlock);

        if (lexer.token() == Token.WINDOW) {
            lexer.nextToken();
            PGSelectQueryBlock.WindowClause window = new PGSelectQueryBlock.WindowClause();
            window.setName(this.expr());
            accept(Token.AS);

            for (;;) {
                SQLExpr expr = this.createExprParser().expr();
                window.getDefinition().add(expr);
                if (lexer.token() == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                } else {
                    break;
                }
            }
            queryBlock.setWindow(window);
        }

        queryBlock.setOrderBy(this.createExprParser().parseOrderBy());

        for (;;) {
            if (lexer.token() == Token.LIMIT) {
                lexer.nextToken();
                if (lexer.token() == Token.ALL) {
                    queryBlock.setLimit(new SQLIdentifierExpr("ALL"));
                    lexer.nextToken();
                } else {
                    SQLExpr limit = expr();
                    queryBlock.setLimit(limit);
                }
            } else if (lexer.token() == Token.OFFSET) {
                lexer.nextToken();
                SQLExpr offset = expr();
                queryBlock.setOffset(offset);

                if (lexer.token() == Token.ROW || lexer.token() == Token.ROWS) {
                    lexer.nextToken();
                }
            } else {
                break;
            }
        }

        if (lexer.token() == Token.FETCH) {
            lexer.nextToken();
            PGSelectQueryBlock.FetchClause fetch = new PGSelectQueryBlock.FetchClause();

            if (lexer.token() == Token.FIRST) {
                fetch.setOption(PGSelectQueryBlock.FetchClause.Option.FIRST);
            } else if (lexer.token() == Token.NEXT) {
                fetch.setOption(PGSelectQueryBlock.FetchClause.Option.NEXT);
            } else {
                throw new ParserException("expect 'FIRST' or 'NEXT'");
            }

            SQLExpr count = expr();
            fetch.setCount(count);

            if (lexer.token() == Token.ROW || lexer.token() == Token.ROWS) {
                lexer.nextToken();
            } else {
                throw new ParserException("expect 'ROW' or 'ROWS'");
            }

            if (lexer.token() == Token.ONLY) {
                lexer.nextToken();
            } else {
                throw new ParserException("expect 'ONLY'");
            }

            queryBlock.setFetch(fetch);
        }

        if (lexer.token() == Token.FOR) {
            lexer.nextToken();

            PGSelectQueryBlock.ForClause forClause = new PGSelectQueryBlock.ForClause();

            if (lexer.token() == Token.UPDATE) {
                forClause.setOption(PGSelectQueryBlock.ForClause.Option.UPDATE);
                lexer.nextToken();
            } else if (lexer.token() == Token.SHARE) {
                forClause.setOption(PGSelectQueryBlock.ForClause.Option.SHARE);
                lexer.nextToken();
            } else {
                throw new ParserException("expect 'FIRST' or 'NEXT'");
            }

            if (lexer.token() == Token.OF) {
                for (;;) {
                    SQLExpr expr = this.createExprParser().expr();
                    forClause.getOf().add(expr);
                    if (lexer.token() == Token.COMMA) {
                        lexer.nextToken();
                        continue;
                    } else {
                        break;
                    }
                }
            }

            if (lexer.token() == Token.NOWAIT) {
                lexer.nextToken();
                forClause.setNoWait(true);
            }

            queryBlock.setForClause(forClause);
        }

        return queryRest(queryBlock);
    }

    protected SQLTableSource parseTableSourceRest(SQLTableSource tableSource) {
        if (lexer.token() == Token.AS && tableSource instanceof SQLExprTableSource) {
            String alias = this.as();

            if (lexer.token() == Token.LPAREN) {
                SQLExprTableSource exprTableSource = (SQLExprTableSource) tableSource;

                PGFunctionTableSource functionTableSource = new PGFunctionTableSource(exprTableSource.getExpr());
                functionTableSource.setAlias(alias);

                lexer.nextToken();
                parserParameters(functionTableSource.getParameters());
                accept(Token.RPAREN);

                return super.parseTableSourceRest(functionTableSource);
            }
        }

        return super.parseTableSourceRest(tableSource);
    }

    private void parserParameters(List<PGParameter> parameters) {
        for (;;) {
            PGParameter parameter = new PGParameter();

            parameter.setName(this.exprParser.name());
            parameter.setDataType(this.exprParser.parseDataType());

            parameters.add(parameter);
            if (lexer.token() == Token.COMMA || lexer.token() == Token.SEMI) {
                lexer.nextToken();
            }

            if (lexer.token() != Token.BEGIN && lexer.token() != Token.RPAREN) {
                continue;
            }

            break;
        }
    }
}
