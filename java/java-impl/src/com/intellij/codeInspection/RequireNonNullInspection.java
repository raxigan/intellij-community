/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection;

import com.intellij.codeInspection.dataFlow.Nullness;
import com.intellij.codeInspection.dataFlow.NullnessUtil;
import com.intellij.codeInspection.util.LambdaGenerationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.ig.psiutils.ClassUtils;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.util.ObjectUtils.tryCast;

public class RequireNonNullInspection extends BaseJavaBatchLocalInspectionTool {
  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    PsiFile file = holder.getFile();
    if(!PsiUtil.isLanguageLevel9OrHigher(file)) {
      return PsiElementVisitor.EMPTY_VISITOR;
    }
    return new JavaRecursiveElementVisitor() {
      @Override
      public void visitIfStatement(PsiIfStatement ifStatement) {
        NotNullContext context = NotNullContext.from(ifStatement);
        if(context == null) return;
        String method = context.getMethod();
        holder.registerProblem(ifStatement, "Replace condition with Objects." + method, new ReplaceWithRequireNonNullFix(method));
      }

      @Override
      public void visitAssignmentExpression(PsiAssignmentExpression assignment) {
        NotNullContext context = NotNullContext.from(assignment);
        if(context == null) return;
        String method = context.getMethod();
        holder.registerProblem(assignment, "Replace condition with Objects." + method, new ReplaceWithRequireNonNullFix(method));
      }
    };
  }

  private static class ReplaceWithRequireNonNullFix implements LocalQuickFix {
    private final @NotNull String myMethod;

    private ReplaceWithRequireNonNullFix(@NotNull String method) {myMethod = method;}

    @Nls
    @NotNull
    @Override
    public String getName() {
      return "Replace condition with Objects." + myMethod;
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace null checked assignment with Objects static method";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiElement element = descriptor.getStartElement();
      final NotNullContext context;
      boolean isExpr = false;
      if(element instanceof PsiIfStatement) {
        context = NotNullContext.from((PsiIfStatement)element);
      } else if(element instanceof PsiAssignmentExpression) {
        context = NotNullContext.from((PsiAssignmentExpression)element);
        isExpr = true;
      } else return;
      if(context == null) return;
      CommentTracker tracker = new CommentTracker();
      boolean isSimple = ExpressionUtils.isSimpleExpression(context.getExpression());
      String expr = tracker.text(context.getExpression());
      if(!isSimple) {
        expr = "()->" + expr;
      }
      String varName = context.getVariable().getName();
      String maybeSemicolon = isExpr ? "" : ";";
      String replacement = varName + "=" + CommonClassNames.JAVA_UTIL_OBJECTS + "." + myMethod + "(" + varName + "," + expr + ")" + maybeSemicolon;
      PsiElement result = tracker.replaceAndRestoreComments(element, replacement);
      LambdaCanBeMethodReferenceInspection.replaceAllLambdasWithMethodReferences(result);
      CodeStyleManager.getInstance(project).reformat(JavaCodeStyleManager.getInstance(project).shortenClassReferences(result));
    }
  }

  private static class NotNullContext {
    private final PsiExpression myExpression;
    private final PsiVariable myVariable;

    private NotNullContext(PsiExpression expression, PsiVariable variable) {
      myExpression = expression;
      myVariable = variable;
    }

    public PsiExpression getExpression() {
      return myExpression;
    }

    public PsiVariable getVariable() {
      return myVariable;
    }

    public String getMethod() {
      boolean isSimple = ExpressionUtils.isSimpleExpression(myExpression);
      return isSimple? "requireNonNullElse" : "requireNonNullElseGet";
    }

    @Nullable
    static NotNullContext from(PsiIfStatement ifStatement) {
      if(ifStatement.getElseBranch() != null) return null;
      PsiStatement statement = ControlFlowUtils.stripBraces(ifStatement.getThenBranch());
      PsiExpression condition = ifStatement.getCondition();
      if(condition == null) return null;
      PsiBinaryExpression binOp = tryCast(condition, PsiBinaryExpression.class);
      if(binOp == null) return null;
      PsiExpression value = ExpressionUtils.getValueComparedWithNull(binOp);
      PsiReferenceExpression referenceExpression = tryCast(value, PsiReferenceExpression.class);
      if(referenceExpression == null) return null;
      PsiVariable variable = tryCast(referenceExpression.resolve(), PsiVariable.class);
      if(variable == null) return null;
      if(ClassUtils.isPrimitive(variable.getType())) return null;
      PsiExpressionStatement expressionStatement = tryCast(statement, PsiExpressionStatement.class);
      if(expressionStatement == null) return null;
      PsiExpression maybeNonNull = ExpressionUtils.getAssignmentTo(expressionStatement.getExpression(), variable);
      if(NullnessUtil.getExpressionNullness(maybeNonNull) != Nullness.NOT_NULL) return null;
      if(!LambdaGenerationUtil.canBeUncheckedLambda(maybeNonNull)) return null;
      return new NotNullContext(maybeNonNull, variable);
    }

    @Nullable
    static NotNullContext from(PsiAssignmentExpression assignment) {
      PsiConditionalExpression ternary = tryCast(assignment.getRExpression(), PsiConditionalExpression.class);
      if(ternary == null) return null;
      PsiReferenceExpression reference = tryCast(assignment.getLExpression(), PsiReferenceExpression.class);
      if(reference == null) return null;
      PsiVariable variable = tryCast(reference.resolve(), PsiVariable.class);

      PsiBinaryExpression binOp = tryCast(ternary.getCondition(), PsiBinaryExpression.class);
      if(binOp == null) return null;
      final boolean negated;
      IElementType tokenType = binOp.getOperationTokenType();
      if(tokenType == JavaTokenType.NE) {
        negated = true;
      } else if(tokenType == JavaTokenType.EQEQ) {
        negated = false;
      } else return null;
      if (!ExpressionUtils.isNullLiteral(ExpressionUtils.getOtherOperand(binOp, variable))) return null;


      if(ClassUtils.isPrimitive(variable.getType())) return null;

      PsiExpression main = negated? ternary.getThenExpression() : ternary.getElseExpression();
      if(!ExpressionUtils.isReferenceTo(main, variable)) return null;
      PsiExpression alternative = negated? ternary.getElseExpression() : ternary.getThenExpression();
      if(NullnessUtil.getExpressionNullness(alternative) != Nullness.NOT_NULL) return null;
      if(!LambdaGenerationUtil.canBeUncheckedLambda(alternative)) return null;
      return new NotNullContext(alternative, variable);
    }
  }
}
