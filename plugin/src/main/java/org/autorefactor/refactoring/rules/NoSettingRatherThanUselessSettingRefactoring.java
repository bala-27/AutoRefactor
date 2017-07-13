/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2017 Fabrice Tiercelin - initial API and implementation
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
import static org.autorefactor.refactoring.ASTHelper.asExpression;
import static org.autorefactor.refactoring.ASTHelper.getNextSibling;
import static org.autorefactor.refactoring.ASTHelper.hasOperator;
import static org.autorefactor.refactoring.ASTHelper.isSameVariable;
import static org.eclipse.jdt.core.dom.Assignment.Operator.ASSIGN;

import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

/** See {@link #getDescription()} method. */
public class NoSettingRatherThanUselessSettingRefactoring extends AbstractRefactoringRule {
    @Override
    public String getDescription() {
        return ""
                + "Remove passive assignment when the variable is reassigned before being read.";
    }

    @Override
    public String getName() {
        return "No setting rather than useless setting";
    }

    @Override
    public boolean visit(VariableDeclarationStatement node) {
        if (node.fragments() != null && node.fragments().size() == 1) {
            final VariableDeclarationFragment fragment = (VariableDeclarationFragment) node.fragments().get(0);
            if (fragment.getInitializer() != null
                    && fragment.getInitializer().resolveConstantExpressionValue() != null) {
                final IVariableBinding variable = fragment.resolveBinding();
                Statement stmtToInspect = getNextSibling(node);
                boolean isOverridden = false;
                boolean isRead = false;
                while (stmtToInspect != null && !isOverridden && !isRead) {
                    if (stmtToInspect instanceof ExpressionStatement) {
                        final Assignment assignment = asExpression(stmtToInspect, Assignment.class);
                        isOverridden = hasOperator(assignment, ASSIGN)
                                && isSameVariable(
                                        fragment.getName(),
                                        assignment.getLeftHandSide());
                    }

                    isRead = !new VariableDefinitionsUsesVisitor(variable, stmtToInspect).find().getUses()
                            .isEmpty();

                    stmtToInspect = getNextSibling(stmtToInspect);
                }

                if (isOverridden && !isRead) {
                    ctx.getRefactorings().remove(fragment.getInitializer());
                    return DO_NOT_VISIT_SUBTREE;
                }
            }
        }
        return VISIT_SUBTREE;
    }
}
