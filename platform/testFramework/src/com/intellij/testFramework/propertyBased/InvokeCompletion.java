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
package com.intellij.testFramework.propertyBased;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.TestLookupElementPresentation;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import junit.framework.TestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jetCheck.Generator;

import java.util.*;

/**
 * @author peter
 */
public class InvokeCompletion extends ActionOnFile {
  private static final Logger LOG = Logger.getInstance("#com.intellij.testFramework.propertyBased.InvokeCompletion");
  private final CompletionPolicy myPolicy;

  public InvokeCompletion(PsiFile file, CompletionPolicy policy) {
    super(file);
    myPolicy = policy;
  }

  @Override
  public void performCommand(@NotNull Environment env) {
    int offset = generateDocOffset(env, "Invoke basic completion at offset %s");
    String selectionCharacters = myPolicy.getPossibleSelectionCharacters();
    char c = selectionCharacters.charAt(env.generateValue(Generator.integers(0, selectionCharacters.length() - 1), null));
    performActionAt(offset, c, env);
  }

  private void performActionAt(int offset, char completionChar, Environment env) {
    Project project = getProject();
    Editor editor =
      FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, getVirtualFile(), 0), true);
    assert editor != null;

    PsiDocumentManager.getInstance(project).commitAllDocuments();

    editor.getCaretModel().moveToOffset(offset);

    CharSequence textBefore = editor.getDocument().getImmutableCharSequence();

    MadTestingUtil.restrictChangesToDocument(editor.getDocument(), () -> {
      Disposable raiseCompletionLimit = Disposer.newDisposable();
      Registry.get("ide.completion.variant.limit").setValue(100_000, raiseCompletionLimit);
      try {
        PsiTestUtil.checkPsiStructureWithCommit(getFile(), PsiTestUtil::checkStubsMatchText);
        Editor caretEditor = InjectedLanguageUtil.getEditorForInjectedLanguageNoCommit(editor, getFile());
        performCompletion(caretEditor, Objects.requireNonNull(PsiUtilBase.getPsiFileInEditor(caretEditor, project)), completionChar, env);
        PsiTestUtil.checkPsiStructureWithCommit(getFile(), PsiTestUtil::checkStubsMatchText);
      }
      catch (Throwable e) {
        LOG.debug("Text before completion:\n" + textBefore);
        env.logMessage("Error happened, the file's text before invoking printed to the debug log, search for 'Text before completion' there");
        throw e;
      }
      finally {
        Disposer.dispose(raiseCompletionLimit);
        LookupManager.getInstance(project).hideActiveLookup();
        UIUtil.dispatchAllInvocationEvents();
      }
    });
  }

  private void performCompletion(@NotNull Editor editor,
                                 @NotNull PsiFile file,
                                 char completionChar,
                                 Environment env) {
    int caretOffset = editor.getCaretModel().getOffset();

    PsiElement leaf = file.findElementAt(TargetElementUtil.adjustOffset(file, getDocument(), caretOffset));
    PsiReference ref = TargetElementUtil.findReference(editor);

    String expectedVariant = leaf == null ? null : myPolicy.getExpectedVariant(editor, file, leaf, ref);
    boolean prefixEqualsExpected = isPrefixEqualToExpectedVariant(caretOffset, leaf, ref, expectedVariant);
    boolean shouldCheckDuplicates = myPolicy.shouldCheckDuplicates(editor, file, leaf);
    long stampBefore = getDocument().getModificationStamp();

    new CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(getProject(), editor);

    String notFound = ". Please either fix completion so that the variant is suggested, " +
                      "or, if absolutely needed, tweak CompletionPolicy to exclude it.";

    LookupEx lookup = LookupManager.getActiveLookup(editor);
    if (lookup == null) {
      if (editor.getCaretModel().getOffset() != caretOffset || getDocument().getModificationStamp() != stampBefore) {
        env.logMessage("Completion item was auto-inserted");
        return;
      }
      env.logMessage("No lookup");
      if (expectedVariant == null || prefixEqualsExpected) return;
      TestCase.fail("No lookup, but expected '" + expectedVariant + "' among completion variants" + notFound);
    }

    List<LookupElement> items = lookup.getItems();
    if (expectedVariant != null) {
      LookupElement sameItem = ContainerUtil.find(items, e -> e.getAllLookupStrings().contains(expectedVariant));
      TestCase.assertNotNull("No variant '" + expectedVariant + "' among " + items + notFound, sameItem);
    }

    if (shouldCheckDuplicates) {
      checkNoDuplicates(items);
    }

    LookupElement item = env.generateValue(Generator.sampledFrom(items), null);
    env.logMessage("Select '" + item + "' with '" + StringUtil.escapeStringCharacters(String.valueOf(completionChar)) + "'");

    if (LookupEvent.isSpecialCompletionChar(completionChar)) {
      ((LookupImpl)lookup).finishLookup(completionChar, item);
    } else {
      EditorActionManager.getInstance().getTypedAction().actionPerformed(editor, completionChar, ((EditorImpl)lookup.getTopLevelEditor()).getDataContext());
    }
  }

  private boolean isPrefixEqualToExpectedVariant(int caretOffset, PsiElement leaf, PsiReference ref, String expectedVariant) {
    if (expectedVariant == null) return false;

    int expectedEnd = ref != null ? ref.getRangeInElement().getEndOffset() + ref.getElement().getTextRange().getStartOffset() :
                      leaf != null ? leaf.getTextRange().getEndOffset() :
                      0;
    return expectedEnd == caretOffset && getFile().getText().substring(0, caretOffset).endsWith(expectedVariant);
  }

  private static void checkNoDuplicates(List<LookupElement> items) {
    Set<List<?>> presentations = new HashSet<>();
    for (LookupElement item : items) {
      LookupElementPresentation p = TestLookupElementPresentation.renderReal(item);
      if (seemsTruncated(p.getItemText()) || seemsTruncated(p.getTailText()) || seemsTruncated(p.getTypeText())) {
        continue;
      }

      List<Object> info = Arrays.asList(TestLookupElementPresentation.unwrapIcon(p.getIcon()),
                                        p.getItemText(), p.getItemTextForeground(), p.isItemTextBold(), p.isItemTextUnderlined(),
                                        p.getTailFragments(),
                                        p.getTypeText(), TestLookupElementPresentation.unwrapIcon(p.getTypeIcon()), p.isTypeGrayed(),
                                        p.isStrikeout());
      if (!presentations.add(info)) {
        TestCase.fail("Duplicate suggestions: " + p);
      }
    }
  }

  private static boolean seemsTruncated(String text) {
    return text != null && text.contains("...");
  }

}
