/*
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 */
package io.github.rosemoe.sora.langs.textmate;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.NonNull;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes;
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.StandardTokenType;
import org.eclipse.tm4e.core.internal.oniguruma.OnigResult;
import org.eclipse.tm4e.core.internal.oniguruma.Oniguruma;
import org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp;
import org.eclipse.tm4e.core.internal.oniguruma.OnigString;
import org.eclipse.tm4e.core.internal.theme.FontStyle;
import org.eclipse.tm4e.core.internal.theme.Theme;
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager;
import io.github.rosemoe.sora.lang.brackets.BracketsProvider;
import io.github.rosemoe.sora.lang.brackets.OnlineBracketsMatcher;
import io.github.rosemoe.sora.lang.completion.IdentifierAutoComplete;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.SpanFactory;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.langs.textmate.folding.FoldingHelper;
import io.github.rosemoe.sora.langs.textmate.folding.IndentRange;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.utils.StringUtils;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class TextMateAnalyzer extends AsyncIncrementalAnalyzeManager<MyState, Span> implements FoldingHelper, ThemeRegistry.ThemeChangeListener {

    private final IGrammar grammar;
    private Theme theme;
    private final TextMateLanguage language;
    private final LanguageConfiguration configuration;

    //private final GrammarRegistry grammarRegistry;

    private final ThemeRegistry themeRegistry;

    private OnigRegExp cachedRegExp;
    private boolean foldingOffside;
    private BracketsProvider bracketsProvider;
    final IdentifierAutoComplete.SyncIdentifiers syncIdentifiers = new IdentifierAutoComplete.SyncIdentifiers();

    // Performance optimization: ThreadLocal pools for reusable objects
    private static final ThreadLocal<ArrayList<Span>> SPAN_LIST_POOL = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<ArrayList<String>> IDENTIFIER_LIST_POOL = ThreadLocal.withInitial(ArrayList::new);
    
    // Performance optimization: Cache for parsed colors to avoid repeated Color.parseColor calls
    private final Map<Integer, Integer> colorCache = new HashMap<>();


    public TextMateAnalyzer(TextMateLanguage language, IGrammar grammar, LanguageConfiguration languageConfiguration,/* GrammarRegistry grammarRegistry,*/ ThemeRegistry themeRegistry) {
        this.language = language;

        this.theme = themeRegistry.getCurrentThemeModel().getTheme();

        this.grammar = grammar;

        //this.grammarRegistry = grammarRegistry;

        this.themeRegistry = themeRegistry;

        if (!themeRegistry.hasListener(this)) {
            themeRegistry.addListener(this);
        }

        if (languageConfiguration != null) {
            configuration = languageConfiguration;
            var pairs = languageConfiguration.getBrackets();
            if (pairs != null && !pairs.isEmpty()) {
                int size = pairs.size();
                for (var pair : pairs) {
                    if (pair.open.length() != 1 || pair.close.length() != 1) {
                        size--;
                    }
                }
                var pairArr = new char[size * 2];
                int i = 0;
                for (var pair : pairs) {
                    if (pair.open.length() != 1 || pair.close.length() != 1) {
                        continue;
                    }
                    pairArr[i * 2] = pair.open.charAt(0);
                    pairArr[i * 2 + 1] = pair.close.charAt(0);
                    i++;
                }
                bracketsProvider = new OnlineBracketsMatcher(pairArr, 100000);
            }
        } else {
            configuration = null;
        }

        createFoldingExp();
    }

    private void createFoldingExp() {
        if (configuration == null) {
            return;
        }
        var markers = configuration.getFolding();
        if (markers == null) return;
        foldingOffside = markers.offSide;
        cachedRegExp = Oniguruma.newRegex("(" + markers.markersStart + ")|(?:" + markers.markersEnd + ")");
    }

    @Override
    public MyState getInitialState() {
        return null;
    }

    @Override
    public boolean stateEquals(MyState state, MyState another) {
        if (state == null && another == null) {
            return true;
        }
        if (state != null && another != null) {
            return Objects.equals(state.tokenizeState, another.tokenizeState);
        }
        return false;
    }

    @Override
    public int getIndentFor(int line) {
        return getState(line).state.indent;
    }

    @Override
    public OnigResult getResultFor(int line) {
        return getState(line).state.foldingCache;
    }

    @Override
    public List<CodeBlock> computeBlocks(Content text, CodeBlockAnalyzeDelegate delegate) {
        var list = new ArrayList<CodeBlock>();
        analyzeCodeBlocks(text, list, delegate);
        if (delegate.isNotCancelled()) {
            withReceiver(r -> r.updateBracketProvider(this, bracketsProvider));
        }
        return list;
    }

    public void analyzeCodeBlocks(Content model, ArrayList<CodeBlock> blocks, CodeBlockAnalyzeDelegate delegate) {
        if (cachedRegExp == null) {
            return;
        }
        try {
            var foldingRegions = IndentRange.computeRanges(model, language.getTabSize(), foldingOffside, this, cachedRegExp, delegate);
            blocks.ensureCapacity(foldingRegions.length());
            for (int i = 0; i < foldingRegions.length() && delegate.isNotCancelled(); i++) {
                int startLine = foldingRegions.getStartLineNumber(i);
                int endLine = foldingRegions.getEndLineNumber(i);
                if (startLine != endLine) {
                    CodeBlock codeBlock = new CodeBlock();
                    codeBlock.toBottomOfEndLine = true;
                    codeBlock.startLine = startLine;
                    codeBlock.endLine = endLine;

                    // It's safe here to use raw data because the Content is only held by this thread
                    var length = model.getColumnCount(startLine);
                    var chars = model.getLine(startLine).getBackingCharArray();

                    codeBlock.startColumn = IndentRange.computeStartColumn(chars, length, language.getTabSize());
                    codeBlock.endColumn = codeBlock.startColumn;
                    blocks.add(codeBlock);
                }
            }
            Collections.sort(blocks, CodeBlock.COMPARATOR_END);
        } catch (Exception e) {
            e.printStackTrace();
        }
        getManagedStyles().setIndentCountMode(true);
    }

    /**
     * Performance optimization: Precompute UTF-16 offsets for all tokens in one pass
     * to avoid O(n²) complexity from repeated conversions.
     * 
     * This method efficiently converts unicode offsets to UTF-16 offsets by doing a single
     * pass through the line string, avoiding the O(n²) behavior of repeatedly calling
     * convertUnicodeOffsetToUtf16 for each token.
     * 
     * @param line The line text
     * @param tokens The token array from grammar (alternating start/metadata pairs)
     * @param tokensLength Number of tokens
     * @param hasSurrogate Whether the line contains surrogate pairs
     * @return Array of UTF-16 offsets for each token start position
     */
    private int[] precomputeUtf16Offsets(String line, int[] tokens, int tokensLength, boolean hasSurrogate) {
        int[] offsets = new int[tokensLength];
        
        if (!hasSurrogate) {
            // Fast path: no surrogates, offsets are identity mapping
            for (int i = 0; i < tokensLength; i++) {
                offsets[i] = tokens[2 * i];
            }
            return offsets;
        }
        
        // Optimized single-pass conversion for surrogate pairs
        // Build a mapping by walking through the string once
        int utf16Index = 0;
        int unicodeIndex = 0;
        int tokenIndex = 0;
        int lineLength = line.length();
        
        while (tokenIndex < tokensLength) {
            int targetUnicodeOffset = tokens[2 * tokenIndex];
            
            // Advance utf16Index until we reach the target unicode offset
            while (unicodeIndex < targetUnicodeOffset && utf16Index < lineLength) {
                char ch = line.charAt(utf16Index);
                if (Character.isHighSurrogate(ch) && utf16Index + 1 < lineLength 
                    && Character.isLowSurrogate(line.charAt(utf16Index + 1))) {
                    utf16Index += 2;
                } else {
                    utf16Index++;
                }
                unicodeIndex++;
            }
            
            // Record the UTF-16 offset for this token
            offsets[tokenIndex] = utf16Index;
            tokenIndex++;
        }
        
        return offsets;
    }

    @Override
    @SuppressLint("NewApi")
    public synchronized LineTokenizeResult<MyState, Span> tokenizeLine(CharSequence lineC, MyState state, int lineIndex) {
        String line = (lineC instanceof ContentLine) ? ((ContentLine) lineC).toStringWithNewline() : lineC.toString();
        
        // Performance optimization: Reuse ArrayList instances from ThreadLocal pool
        var tokens = SPAN_LIST_POOL.get();
        tokens.clear();
        
        var surrogate = StringUtils.checkSurrogate(line);
        var lineTokens = grammar.tokenizeLine2(line, state == null ? null : state.tokenizeState, Duration.ofSeconds(2));
        int tokensLength = lineTokens.getTokens().length / 2;
        
        // Performance optimization: Precompute all UTF-16 offsets at once
        int[] utf16Offsets = precomputeUtf16Offsets(line, lineTokens.getTokens(), tokensLength, surrogate);
        
        // Performance optimization: Reuse identifier list from ThreadLocal pool
        var identifiers = language.createIdentifiers ? IDENTIFIER_LIST_POOL.get() : null;
        if (identifiers != null) {
            identifiers.clear();
        }
        
        for (int i = 0; i < tokensLength; i++) {
            int startIndex = utf16Offsets[i];
            if (i == 0 && startIndex != 0) {
                tokens.add(SpanFactory.obtainNoExt(0, EditorColorScheme.TEXT_NORMAL));
            }
            int metadata = lineTokens.getTokens()[2 * i + 1];
            int foreground = EncodedTokenAttributes.getForeground(metadata);
            int fontStyle = EncodedTokenAttributes.getFontStyle(metadata);
            var tokenType = EncodedTokenAttributes.getTokenType(metadata);
            if (language.createIdentifiers) {

                if (tokenType == StandardTokenType.Other) {
                    var end = i + 1 == tokensLength ? lineC.length() : utf16Offsets[i + 1];
                    if (end > startIndex && MyCharacter.isJavaIdentifierStart(line.charAt(startIndex))) {
                        var flag = true;
                        for (int j = startIndex + 1; j < end; j++) {
                            if (!MyCharacter.isJavaIdentifierPart(line.charAt(j))) {
                                flag = false;
                                break;
                            }
                        }
                        if (flag) {
                            identifiers.add(line.substring(startIndex, end));
                        }
                    }
                }
            }
            Span span = SpanFactory.obtainNoExt(startIndex, TextStyle.makeStyle(foreground + 255, 0, (fontStyle & FontStyle.Bold) != 0, (fontStyle & FontStyle.Italic) != 0, false));

            span.setExtra(tokenType);

            if ((fontStyle & FontStyle.Underline) != 0) {
                // Performance optimization: Use cached parsed color to avoid repeated Color.parseColor calls
                Integer cachedColor = colorCache.get(foreground);
                if (cachedColor == null) {
                    String color = theme.getColor(foreground);
                    if (color != null) {
                        cachedColor = Color.parseColor(color);
                        colorCache.put(foreground, cachedColor);
                    }
                }
                if (cachedColor != null) {
                    span.setUnderlineColor(cachedColor);
                }
            }

            tokens.add(span);
        }
        
        // Create a copy of the lists for the state since we're reusing the ThreadLocal instances
        var tokensCopy = new ArrayList<>(tokens);
        var identifiersCopy = identifiers != null ? new ArrayList<>(identifiers) : null;
        
        return new LineTokenizeResult<>(new MyState(lineTokens.getRuleStack(), cachedRegExp == null ? null : cachedRegExp.search(OnigString.of(line), 0), IndentRange.computeIndentLevel(((ContentLine) lineC).getBackingCharArray(), line.length() - 1, language.getTabSize()), identifiersCopy), null, tokensCopy);
    }

    @Override
    public void onAddState(MyState state) {
        super.onAddState(state);
        if (language.createIdentifiers) {
            for (String identifier : state.identifiers) {
                syncIdentifiers.identifierIncrease(identifier);
            }
        }
    }

    @Override
    public void onAbandonState(MyState state) {
        super.onAbandonState(state);
        if (language.createIdentifiers) {
            for (String identifier : state.identifiers) {
                syncIdentifiers.identifierDecrease(identifier);
            }
        }
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        super.reset(content, extraArguments);
        syncIdentifiers.clear();
    }

    @Override
    public void destroy() {
        super.destroy();
        themeRegistry.removeListener(this);
    }

    @Override
    public List<Span> generateSpansForLine(LineTokenizeResult<MyState, Span> tokens) {
        return null;
    }

    @Override
    public void onChangeTheme(ThemeModel newTheme) {
        this.theme = newTheme.getTheme();
        // Clear color cache when theme changes since color values will be different
        colorCache.clear();
    }
}
