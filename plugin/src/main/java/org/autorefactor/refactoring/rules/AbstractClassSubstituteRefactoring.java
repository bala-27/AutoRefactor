/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2017 Fabrice Tiercelin - initial API and implementation
 * Copyright (C) 2017 Jean-Noël Rouvignac - fix NPE with Eclipse 4.5.2
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
import static org.autorefactor.refactoring.ASTHelper.hasType;

import java.util.ArrayList;
import java.util.List;

import org.autorefactor.refactoring.ASTBuilder;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
/** See {@link #getDescription()} method. */
public abstract class AbstractClassSubstituteRefactoring extends AbstractRefactoringRule {

    /**
     * Get the existing class canonical name.
     *
     * @return the existing class canonical name.
     */
    protected abstract String getExistingClassCanonicalName();

    /**
     * Get the substituting class name.
     *
     * @return the substituting class name.
     */
    protected abstract String getSubstitutingClassName();

    /**
     * If a local variable can be used in a runnable.
     *
     * @return True if a local variable can be used in a runnable.
     */
    protected boolean canBeSharedInOtherThread() {
        return true;
    }

    /**
     * If the instantiation can be refactored.
     *
     * @param instanceCreation The instantiation
     * @return True if the instantiation can be refactored.
     */
    protected boolean canInstantiationBeRefactored(final ClassInstanceCreation instanceCreation) {
        return true;
    }

    /**
     * Is the method returning existing class.
     *
     * @param mi The method invocation
     * @return True if the method returns the existing class.
     */
    protected boolean isMethodReturningExistingClass(final MethodInvocation mi) {
        return false;
    }

    /**
     * Refactor the constructor.
     *
     * @param b The builder.
     * @param originalInstanceCreation The original instance creation.
     * @param newInstanceCreation The new instance creation.
     */
    protected void refactorInstantiation(final ASTBuilder b, final ClassInstanceCreation originalInstanceCreation,
            final ClassInstanceCreation newInstanceCreation) {
        newInstanceCreation.setType(substituteType(b, originalInstanceCreation.getType()));
    }

    /**
     * If the method can be refactored.
     *
     * @param mi The method invocation
     * @param methodCallsToRefactor The method calls to refactor
     * @return True if the method can be refactored.
     */
    protected boolean canMethodBeRefactored(final MethodInvocation mi,
            final List<MethodInvocation> methodCallsToRefactor) {
        return true;
    }

    /**
     * Refactor the method.
     *
     * @param b The builder
     * @param originalMi The original method invocation
     * @param refactoredMi The new method invocation
     */
    protected void refactorMethod(final ASTBuilder b, final MethodInvocation originalMi,
            final MethodInvocation refactoredMi) {
    }

    /**
     * Refactor the variable.
     *
     * @param b The builder
     * @param oldDeclareStmt The original variable declaration
     * @param newDeclareStmt The new variable declaration
     */
    protected void replaceVariableType(final ASTBuilder b, final VariableDeclarationStatement oldDeclareStmt,
            final VariableDeclarationStatement newDeclareStmt) {
        newDeclareStmt.setType(substituteType(b, oldDeclareStmt.getType()));
    }

    @Override
    public boolean visit(Block node) {
        final ObjectInstantiationVisitor classCreationVisitor = new ObjectInstantiationVisitor();
        node.accept(classCreationVisitor);

        for (final ClassInstanceCreation instanceCreation : classCreationVisitor.getObjectInstantiations()) {
            final List<VariableDeclaration> varDecls = new ArrayList<VariableDeclaration>();
            final List<MethodInvocation> methodCallsToRefactorAlone = new ArrayList<MethodInvocation>();
            final List<MethodInvocation> methodCallsToRefactorWithVariable = new ArrayList<MethodInvocation>();

            if (canInstantiationBeRefactored(instanceCreation)
                    && canBeRefactored(node, instanceCreation, varDecls,
                            methodCallsToRefactorAlone,
                            methodCallsToRefactorWithVariable)) {
                replaceClass(instanceCreation, varDecls, methodCallsToRefactorAlone,
                        methodCallsToRefactorWithVariable);
                return DO_NOT_VISIT_SUBTREE;
            }
        }

        return VISIT_SUBTREE;
    }

    private boolean canBeRefactored(Block node, final ASTNode itemToRefactor,
            final List<VariableDeclaration> varDecls, final List<MethodInvocation> methodCallsToRefactorAlone,
            final List<MethodInvocation> methodCallsToRefactorWithVariable) {
        return canInstantiationBeRefactored(itemToRefactor, varDecls, methodCallsToRefactorAlone,
                methodCallsToRefactorWithVariable)
                && canVarOccurrenceBeRefactored(node, varDecls, methodCallsToRefactorAlone,
                        methodCallsToRefactorWithVariable);
    }

    private boolean canVarOccurrenceBeRefactored(final Block node, final List<VariableDeclaration> varDecls,
            final List<MethodInvocation> methodCallsToRefactorAlone,
            final List<MethodInvocation> methodCallsToRefactorWithVariable) {
        final List<VariableDeclaration> otherVarDecls = new ArrayList<VariableDeclaration>();
        final boolean canBeRefactored = canVarOccurrenceBeRefactored0(node,
                                                                      varDecls,
                                                                      methodCallsToRefactorAlone,
                                                                      methodCallsToRefactorWithVariable,
                                                                      otherVarDecls);
        varDecls.addAll(otherVarDecls);
        return canBeRefactored;
    }

    private boolean canVarOccurrenceBeRefactored0(final Block node, final List<VariableDeclaration> varDecls,
                                                  final List<MethodInvocation> methodCallsToRefactorAlone,
                                                  final List<MethodInvocation> methodCallsToRefactorWithVariable,
                                                  final List<VariableDeclaration> otherVarDecls) {
        for (final VariableDeclaration varDecl : varDecls) {
            final VarOccurrenceVisitor varOccurrenceVisitor = new VarOccurrenceVisitor(varDecl);
            node.accept(varOccurrenceVisitor);
            if (varOccurrenceVisitor.isUsedInAnnonymousClass()) {
                return false;
            }

            for (final SimpleName varOccurrence : varOccurrenceVisitor.getVarOccurrences()) {
                final List<VariableDeclaration> subVarDecls = new ArrayList<VariableDeclaration>();
                if (!canBeRefactored(node, varOccurrence, subVarDecls,
                                     methodCallsToRefactorAlone, methodCallsToRefactorWithVariable)) {
                    return false;
                }
                otherVarDecls.addAll(subVarDecls);
            }
        }
        return true;
    }

    private void replaceClass(final ClassInstanceCreation originalInstanceCreation,
            final List<VariableDeclaration> variableDecls,
            final List<MethodInvocation> methodCallsToRefactorAlone,
            final List<MethodInvocation> methodCallsToRefactorWithVariable) {
        final ASTBuilder b = ctx.getASTBuilder();

        if (variableDecls.isEmpty() && methodCallsToRefactorAlone.isEmpty()) {
            final ClassInstanceCreation newInstanceCreation = b.copySubtree(originalInstanceCreation);
            refactorInstantiation(b, originalInstanceCreation, newInstanceCreation);
            ctx.getRefactorings().replace(originalInstanceCreation, newInstanceCreation);
        } else {
            refactorInstantiation(b, originalInstanceCreation, originalInstanceCreation);

            for (final MethodInvocation methodCall : methodCallsToRefactorAlone) {
                final MethodInvocation copyOfMethodCall = b.copySubtree(methodCall);
                refactorMethod(b, methodCall, copyOfMethodCall);
                ctx.getRefactorings().replace(methodCall, copyOfMethodCall);
            }

            for (final MethodInvocation methodCall : methodCallsToRefactorWithVariable) {
                refactorMethod(b, methodCall, methodCall);
            }

            for (final VariableDeclaration variableDecl : variableDecls) {
                final VariableDeclarationStatement parent = (VariableDeclarationStatement) variableDecl.getParent();
                final VariableDeclarationStatement newDeclareStmt = b.copySubtree(parent);

                replaceVariableType(b, parent, newDeclareStmt);
                ctx.getRefactorings().replace(parent, newDeclareStmt);
            }
        }
    }

    private Type substituteType(final ASTBuilder b, final Type origType) {
        if (origType.isParameterizedType()) {
            final Type[] types = typeArgumentsToTypes(b, origType);
            return b.genericType(getSubstitutingClassName(), types);
        }
        return b.type(getSubstitutingClassName());
    }

    private Type[] typeArgumentsToTypes(final ASTBuilder b, final Type type) {
        final ITypeBinding[] typeArgs = type.resolveBinding().getTypeArguments();
        final Type[] types = new Type[typeArgs.length];
        for (int i = 0; i < typeArgs.length; i++) {
            types[i] = b.type(typeArgs[i].getName());
        }
        return types;
    }

    private boolean canInstantiationBeRefactored(final ASTNode node,
            final List<VariableDeclaration> variablesToRefactor,
            final List<MethodInvocation> methodCallsToRefactorAlone,
            final List<MethodInvocation> methodCallsToRefactorWithVariable) {
        final List<MethodInvocation> localMethodCallsToRefactor = new ArrayList<MethodInvocation>();

        ASTNode childNode = node;

        do {
            ASTNode parentNode = childNode.getParent();

            if (parentNode instanceof ReturnStatement
                    || parentNode instanceof Assignment) {
                return false;
            } else if (parentNode instanceof VariableDeclaration) {
                final VariableDeclaration varDecl = (VariableDeclaration) parentNode;
                final VariableDeclarationStatement variableDeclaration =
                        (VariableDeclarationStatement) varDecl.getParent();
                if (hasType(variableDeclaration.getType().resolveBinding(), getExistingClassCanonicalName())) {
                    variablesToRefactor.add(varDecl);
                    methodCallsToRefactorWithVariable.addAll(localMethodCallsToRefactor);
                    return true;
                }
                return false;
            } else if (parentNode instanceof MethodInvocation) {
                final MethodInvocation mi = (MethodInvocation) parentNode;
                if (isObjectPassedInParameter(childNode, mi)
                        || !canMethodBeRefactored(mi, localMethodCallsToRefactor)) {
                    return false;
                } else if (!isMethodReturningExistingClass(mi)) {
                    methodCallsToRefactorAlone.addAll(localMethodCallsToRefactor);
                    return true;
                }
            } else if ((parentNode instanceof CastExpression)
                    || (parentNode instanceof InstanceofExpression)) {
                return false;
            } else if (!(parentNode instanceof ParenthesizedExpression)) {
                methodCallsToRefactorAlone.addAll(localMethodCallsToRefactor);
                return true;
            }

            childNode = parentNode;
        } while (true);
    }

    private boolean isObjectPassedInParameter(final ASTNode subNode, final MethodInvocation mi) {
        return !mi.getExpression().equals(subNode);
    }

    private final class ObjectInstantiationVisitor extends ASTVisitor {

        private final List<ClassInstanceCreation> objectInstantiations = new ArrayList<ClassInstanceCreation>();

        public List<ClassInstanceCreation> getObjectInstantiations() {
            return objectInstantiations;
        }

        @Override
        public boolean visit(ClassInstanceCreation instanceCreation) {
            final ITypeBinding typeBinding;
            if (instanceCreation.getType() != null) {
                typeBinding = instanceCreation.getType().resolveBinding();
            } else {
                typeBinding = instanceCreation.resolveTypeBinding();
            }

            if (hasType(typeBinding, getExistingClassCanonicalName())) {
                objectInstantiations.add(instanceCreation);
            }
            return VISIT_SUBTREE;
        }
    }

    private class VarOccurrenceVisitor extends ASTVisitor {

        private final VariableDeclaration varDecl;
        private final List<SimpleName> varOccurrences = new ArrayList<SimpleName>();
        private boolean isUsedInAnnonymousClass;

        public VarOccurrenceVisitor(VariableDeclaration variable) {
            varDecl = variable;
        }

        public List<SimpleName> getVarOccurrences() {
            return varOccurrences;
        }

        public boolean isUsedInAnnonymousClass() {
            return isUsedInAnnonymousClass;
        }

        @Override
        public boolean visit(SimpleName aVariable) {
            final SimpleName varDeclName = varDecl.getName();
            if (aVariable.getIdentifier().equals(varDeclName.getIdentifier())
                    && !aVariable.equals(varDeclName)) {
                varOccurrences.add(aVariable);
            }
            return VISIT_SUBTREE;
        }

        @Override
        public boolean visit(AnonymousClassDeclaration node) {
            if (!canBeSharedInOtherThread()) {
                final VariableDefinitionsUsesVisitor variableUseVisitor =
                        new VariableDefinitionsUsesVisitor(varDecl.resolveBinding(), node).find();
                if (!variableUseVisitor.getUses().isEmpty()) {
                    isUsedInAnnonymousClass = true;
                    return DO_NOT_VISIT_SUBTREE;
                }
            }
            return VISIT_SUBTREE;
        }
    }
}