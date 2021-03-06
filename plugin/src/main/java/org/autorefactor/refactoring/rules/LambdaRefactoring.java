/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2018 Fabrice Tiercelin - Initial API and implementation
 * Copyright (C) 2018 Jean-Noël Rouvignac - fix NPE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.refactoring.rules;

import static org.autorefactor.refactoring.ASTHelper.DO_NOT_VISIT_SUBTREE;
import static org.autorefactor.refactoring.ASTHelper.VISIT_SUBTREE;
import static org.autorefactor.refactoring.ASTHelper.arguments;
import static org.autorefactor.refactoring.ASTHelper.asList;
import static org.autorefactor.refactoring.ASTHelper.getCalledType;
import static org.autorefactor.refactoring.ASTHelper.isMethod;
import static org.autorefactor.refactoring.ASTHelper.removeParentheses;

import java.util.List;

import org.autorefactor.refactoring.ASTBuilder;
import org.autorefactor.refactoring.BlockSubVisitor;
import org.autorefactor.refactoring.TypeNameDecider;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/** See {@link #getDescription()} method. */
public class LambdaRefactoring extends AbstractRefactoringRule {
    /**
     * Get the name.
     *
     * @return the name.
     */
    public String getName() {
        return "Improve lambda expressions";
    }

    /**
     * Get the description.
     *
     * @return the description.
     */
    public String getDescription() {
        return "Improve lambda expressions.";
    }

    /**
     * Get the reason.
     *
     * @return the reason.
     */
    public String getReason() {
        return "It reduces code to focus attention on code that matters.";
    }

    private int getJavaMinorVersion() {
        return ctx.getJavaProjectOptions().getJavaSERelease().getMinorVersion();
    }

    @Override
    public boolean visit(Block node) {
        if (getJavaMinorVersion() >= 8) {
            final LambdaExprVisitor lambdaExprVisitor = new LambdaExprVisitor(node);
            node.accept(lambdaExprVisitor);
            return lambdaExprVisitor.getResult();
        }
        return VISIT_SUBTREE;
    }

    private final class LambdaExprVisitor extends BlockSubVisitor {
        public LambdaExprVisitor(final Block startNode) {
            super(LambdaRefactoring.this.ctx, startNode);
        }

        @Override
        public boolean visit(LambdaExpression node) {
            if (node.hasParentheses()
                    && node.parameters().size() == 1
                    && node.parameters().get(0) instanceof VariableDeclarationFragment) {
                // FIXME: it should also be possible to deal with a SingleVariableDeclaration
                // when the type matches the expected inferred type
                removeParamParentheses(node);
                return DO_NOT_VISIT_SUBTREE;
            } else if (node.getBody() instanceof Block) {
                final List<Statement> stmts = asList((Block) node.getBody());

                if (stmts.size() == 1 && stmts.get(0) instanceof ReturnStatement) {
                    removeReturnAndBrackets(node, stmts);
                    return DO_NOT_VISIT_SUBTREE;
                }
            } else if (node.getBody() instanceof ClassInstanceCreation) {
                final ClassInstanceCreation ci = (ClassInstanceCreation) node.getBody();

                final List<Expression> arguments = arguments(ci);
                if (node.parameters().size() == arguments.size()
                        && isSameIdentifier(node, arguments)) {
                    replaceByCreationReference(node, ci);
                    return DO_NOT_VISIT_SUBTREE;
                }
            } else if (node.getBody() instanceof SuperMethodInvocation) {
                final SuperMethodInvocation smi = (SuperMethodInvocation) node.getBody();

                final List<Expression> arguments = arguments(smi);
                if (node.parameters().size() == arguments.size()
                        && isSameIdentifier(node, arguments)) {
                    replaceBySuperMethodReference(node, smi);
                    return DO_NOT_VISIT_SUBTREE;
                }
            } else if (node.getBody() instanceof MethodInvocation) {
                final MethodInvocation mi = (MethodInvocation) node.getBody();
                final Expression calledExpr = mi.getExpression();
                final ITypeBinding calledType = getCalledType(mi);

                final List<Expression> arguments = arguments(mi);
                if (node.parameters().size() == arguments.size()) {
                    if (!isSameIdentifier(node, arguments)) {
                        return VISIT_SUBTREE;
                    }

                    if (isStaticMethod(mi)) {
                        if (!arguments.isEmpty()) {
                            final String[] remainingParams = new String[arguments.size() - 1];
                            for (int i = 0; i < arguments.size() - 1; i++) {
                                remainingParams[i] =
                                        arguments.get(i + 1).resolveTypeBinding().getQualifiedName();
                            }

                            for (final IMethodBinding methodBinding : calledType.getDeclaredMethods()) {
                                if ((methodBinding.getModifiers() & Modifier.STATIC) == 0
                                        && isMethod(methodBinding,
                                                calledType.getQualifiedName(),
                                                mi.getName().getIdentifier(), remainingParams)) {
                                    return VISIT_SUBTREE;
                                }
                            }
                        }

                        replaceByTypeReference(node, mi);
                        return DO_NOT_VISIT_SUBTREE;
                    }

                    if (calledExpr == null
                            || calledExpr instanceof StringLiteral
                            || calledExpr instanceof NumberLiteral
                            || calledExpr instanceof ThisExpression) {
                        replaceByMethodReference(node, mi);
                        return DO_NOT_VISIT_SUBTREE;
                    } else if (calledExpr instanceof FieldAccess) {
                        final FieldAccess fieldAccess = (FieldAccess) calledExpr;
                        if (fieldAccess.resolveFieldBinding().isEffectivelyFinal()) {
                            replaceByMethodReference(node, mi);
                            return DO_NOT_VISIT_SUBTREE;
                        }
                    } else if (calledExpr instanceof SuperFieldAccess) {
                        final SuperFieldAccess fieldAccess = (SuperFieldAccess) calledExpr;
                        if (fieldAccess.resolveFieldBinding().isEffectivelyFinal()) {
                            replaceByMethodReference(node, mi);
                            return DO_NOT_VISIT_SUBTREE;
                        }
                    }
                } else if (calledExpr instanceof SimpleName
                        && node.parameters().size() == arguments.size() + 1) {
                    final SimpleName calledObject = (SimpleName) calledExpr;
                    if (isSameIdentifier(node, 0, calledObject)) {
                        for (int i = 0; i < arguments.size(); i++) {
                            final ASTNode expr = removeParentheses(arguments.get(i));
                            if (!(expr instanceof SimpleName)
                                    || !isSameIdentifier(node, i + 1, (SimpleName) expr)) {
                                return VISIT_SUBTREE;
                            }
                        }

                        final ITypeBinding clazz = calledExpr.resolveTypeBinding();
                        final String[] remainingParams = new String[arguments.size() + 1];
                        remainingParams[0] = clazz.getQualifiedName();
                        for (int i = 0; i < arguments.size(); i++) {
                            remainingParams[i + 1] =
                                    arguments.get(i).resolveTypeBinding().getQualifiedName();
                        }

                        for (IMethodBinding methodBinding : clazz.getDeclaredMethods()) {
                            if ((methodBinding.getModifiers() & Modifier.STATIC) > 0
                                    && isMethod(methodBinding,
                                            clazz.getQualifiedName(),
                                            mi.getName().getIdentifier(), remainingParams)) {
                                return VISIT_SUBTREE;
                            }
                        }

                        replaceByTypeReference(node, mi);
                        return DO_NOT_VISIT_SUBTREE;
                    }
                }
            }
            return VISIT_SUBTREE;
        }

        private boolean isStaticMethod(final MethodInvocation mi) {
            final Expression calledExpr = mi.getExpression();

            if (calledExpr == null) {
                return (mi.resolveMethodBinding().getModifiers() & Modifier.STATIC) != 0;
            } else if (calledExpr instanceof SimpleName) {
                return ((SimpleName) calledExpr).resolveBinding().getKind() == IBinding.TYPE;
            }

            return false;
        }

        private boolean isSameIdentifier(LambdaExpression node, List<Expression> arguments) {
            for (int i = 0; i < node.parameters().size(); i++) {
                if (!isSameIdentifier(node, arguments, i)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isSameIdentifier(final LambdaExpression node, final List<Expression> arguments, final int i) {
            final Expression expr = removeParentheses(arguments.get(i));
            return expr instanceof SimpleName && isSameIdentifier(node, i, (SimpleName) expr);
        }

        private boolean isSameIdentifier(final LambdaExpression node, final int i, final SimpleName argument) {
            final Object param0 = node.parameters().get(i);
            if (param0 instanceof VariableDeclarationFragment) {
                final VariableDeclarationFragment vdf = (VariableDeclarationFragment) param0;
                return vdf.getName().getIdentifier().equals(argument.getIdentifier());
            // } else if (param0 instanceof SingleVariableDeclaration) {
                // FIXME: it should also be possible to deal with a SingleVariableDeclaration
                // when the type matches the expected inferred type
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        private void removeParamParentheses(final LambdaExpression node) {
            final ASTBuilder b = ctx.getASTBuilder();

            final LambdaExpression copyOfLambdaExpr = b.lambda();
            final ASTNode copyOfParameter = b.copy((ASTNode) node.parameters().get(0));
            copyOfLambdaExpr.parameters().add(copyOfParameter);
            copyOfLambdaExpr.setBody(b.copy(node.getBody()));
            copyOfLambdaExpr.setParentheses(false);
            ctx.getRefactorings().replace(node, copyOfLambdaExpr);
        }

        private void removeReturnAndBrackets(final LambdaExpression node, final List<Statement> stmts) {
            final ASTBuilder b = ctx.getASTBuilder();

            final ReturnStatement returnStmt = (ReturnStatement) stmts.get(0);
            ctx.getRefactorings().replace(node.getBody(),
                    b.parenthesizeIfNeeded(b.copy(returnStmt.getExpression())));
        }

        private void replaceByCreationReference(final LambdaExpression node, final ClassInstanceCreation ci) {
            final ASTBuilder b = ctx.getASTBuilder();

            final TypeNameDecider typeNameDecider = new TypeNameDecider(ci);

            final CreationReference creationRef = b.creationRef();
            creationRef.setType(b.toType(ci.resolveTypeBinding().getErasure(), typeNameDecider));
            ctx.getRefactorings().replace(node, creationRef);
        }

        private void replaceBySuperMethodReference(final LambdaExpression node, final SuperMethodInvocation ci) {
            final ASTBuilder b = ctx.getASTBuilder();

            final SuperMethodReference creationRef = b.superMethodRef();
            creationRef.setName(b.copy(ci.getName()));
            ctx.getRefactorings().replace(node, creationRef);
        }

        private void replaceByTypeReference(final LambdaExpression node, final MethodInvocation mi) {
            final ASTBuilder b = ctx.getASTBuilder();

            final TypeNameDecider typeNameDecider = new TypeNameDecider(mi);

            final TypeMethodReference typeMethodRef = b.typeMethodRef();
            typeMethodRef.setType(b.toType(getCalledType(mi).getErasure(), typeNameDecider));
            typeMethodRef.setName(b.copy(mi.getName()));
            ctx.getRefactorings().replace(node, typeMethodRef);
        }

        private void replaceByMethodReference(final LambdaExpression node, final MethodInvocation mi) {
            final ASTBuilder b = ctx.getASTBuilder();

            final ExpressionMethodReference typeMethodRef = b.exprMethodRef();
            if (mi.getExpression() != null) {
                typeMethodRef.setExpression(b.copy(mi.getExpression()));
            } else {
                typeMethodRef.setExpression(b.this0());
            }
            typeMethodRef.setName(b.copy(mi.getName()));
            ctx.getRefactorings().replace(node, typeMethodRef);
        }
    }
}
