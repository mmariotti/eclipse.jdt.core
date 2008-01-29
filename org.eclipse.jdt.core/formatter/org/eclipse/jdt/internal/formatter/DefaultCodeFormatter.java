/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.formatter;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Scanner;
import org.eclipse.jdt.internal.compiler.util.Util;
import org.eclipse.jdt.internal.core.util.CodeSnippetParsingUtil;
import org.eclipse.jdt.internal.formatter.comment.CommentRegion;
import org.eclipse.jdt.internal.formatter.comment.JavaDocRegion;
import org.eclipse.jdt.internal.formatter.comment.MultiCommentRegion;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;

public class DefaultCodeFormatter extends CodeFormatter {

	public static final boolean DEBUG = false;
	private static Scanner ProbingScanner;

	/**
	 * Creates a comment region for a specific document partition type.
	 * 
	 * @param kind the comment snippet kind
	 * @param document the document which contains the comment region
	 * @param range range of the comment region in the document
	 * @return a new comment region for the comment region range in the
	 *         document
	 * @since 3.1
	 */
	public static CommentRegion createRegion(int kind, IDocument document, Position range, CodeFormatterVisitor formatter) {
		switch (kind) {
			case CodeFormatter.K_SINGLE_LINE_COMMENT:
				return new CommentRegion(document, range, formatter);
			case CodeFormatter.K_MULTI_LINE_COMMENT:
				return new MultiCommentRegion(document, range, formatter);
			case CodeFormatter.K_JAVA_DOC:
				return new JavaDocRegion(document, range, formatter);
		}
		return null;
	}

	private CodeSnippetParsingUtil codeSnippetParsingUtil;
	private Map defaultCompilerOptions;
	
	private CodeFormatterVisitor newCodeFormatter;
	private Map options;
	
	private DefaultCodeFormatterOptions preferences;
	
	public DefaultCodeFormatter() {
		this(new DefaultCodeFormatterOptions(DefaultCodeFormatterConstants.getJavaConventionsSettings()), null);
	}
	
	public DefaultCodeFormatter(DefaultCodeFormatterOptions preferences) {
		this(preferences, null);
	}

	public DefaultCodeFormatter(DefaultCodeFormatterOptions defaultCodeFormatterOptions, Map options) {
		if (options != null) {
			this.options = options;
			this.preferences = new DefaultCodeFormatterOptions(options);
		} else {
			this.options = JavaCore.getOptions();
			this.preferences = new DefaultCodeFormatterOptions(DefaultCodeFormatterConstants.getJavaConventionsSettings());
		}
		this.defaultCompilerOptions = getDefaultCompilerOptions();
		if (defaultCodeFormatterOptions != null) {
			this.preferences.set(defaultCodeFormatterOptions.getMap());
		}
	}

	public DefaultCodeFormatter(Map options) {
		this(null, options);
	}
	
	public String createIndentationString(final int indentationLevel) {
		if (indentationLevel < 0) {
			throw new IllegalArgumentException();
		}
		
		int tabs = 0;
		int spaces = 0;
		switch(this.preferences.tab_char) {
			case DefaultCodeFormatterOptions.SPACE :
				spaces = indentationLevel * this.preferences.tab_size;
				break;
			case DefaultCodeFormatterOptions.TAB :
				tabs = indentationLevel;
				break;
			case DefaultCodeFormatterOptions.MIXED :
				int tabSize = this.preferences.tab_size;
				int spaceEquivalents = indentationLevel * this.preferences.indentation_size;
				tabs = spaceEquivalents / tabSize;
				spaces = spaceEquivalents % tabSize;
				break;
			default:
				return Util.EMPTY_STRING;
		}
		if (tabs == 0 && spaces == 0) {
			return Util.EMPTY_STRING;
		}
		StringBuffer buffer = new StringBuffer(tabs + spaces);
		for(int i = 0; i < tabs; i++) {
			buffer.append('\t');
		}
		for(int i = 0; i < spaces; i++) {
			buffer.append(' ');
		}
		return buffer.toString();
	}
	
	/**
	 * @see org.eclipse.jdt.core.formatter.CodeFormatter#format(int, java.lang.String, int, int, int, java.lang.String)
	 */
	public TextEdit format(
			int kind,
			String source,
			int offset,
			int length,
			int indentationLevel,
			String lineSeparator) {

		if (offset < 0 || length < 0 || length > source.length()) {
			throw new IllegalArgumentException();
		}
		switch(kind) {
			case K_JAVA_DOC :
			case K_MULTI_LINE_COMMENT :
			case K_SINGLE_LINE_COMMENT :
				this.codeSnippetParsingUtil = new CodeSnippetParsingUtil();
				return formatComment(kind, source, indentationLevel, lineSeparator, new IRegion[] {new Region(offset, length)});
		}

		return format(kind, source, new IRegion[] {new Region(offset, length)}, indentationLevel, lineSeparator);
	}
	
	/**
	 * {@inheritDoc}
	 */
	public TextEdit format(int kind, String source, IRegion[] regions, int indentationLevel, String lineSeparator) {
		if (!regionsSatisfiesPreconditions(regions, source.length())) {
			throw new IllegalArgumentException();
		}

		this.codeSnippetParsingUtil = new CodeSnippetParsingUtil();
		switch(kind) {
			case K_CLASS_BODY_DECLARATIONS :
				return formatClassBodyDeclarations(source, indentationLevel, lineSeparator, regions);
			case K_COMPILATION_UNIT :
				return formatCompilationUnit(source, indentationLevel, lineSeparator, regions);
			case K_EXPRESSION :
				return formatExpression(source, indentationLevel, lineSeparator, regions);
			case K_STATEMENTS :
				return formatStatements(source, indentationLevel, lineSeparator, regions);
			case K_UNKNOWN :
				return probeFormatting(source, indentationLevel, lineSeparator, regions);
			case K_JAVA_DOC :
			case K_MULTI_LINE_COMMENT :
			case K_SINGLE_LINE_COMMENT :
				//https://bugs.eclipse.org/bugs/show_bug.cgi?id=204091
				throw new IllegalArgumentException();
		}
		return null;
	}

	/**
	 * True if
	 * 1. All regions are within maxLength
	 * 2. regions are sorted
	 * 3. regions are not overlapping
	 */
	private boolean regionsSatisfiesPreconditions(IRegion[] regions, int maxLength) {
		int regionsLength = regions == null ? 0 : regions.length;
		if (regionsLength == 0) {
			return false;
		}

		IRegion first = regions[0];
		if (first.getOffset() < 0 || first.getLength() < 0 || first.getOffset() + first.getLength() > maxLength) {
			return false;
		}

		int lastOffset = first.getOffset() + first.getLength() - 1;
		for (int i= 1; i < regionsLength; i++) {
			IRegion current = regions[i];
			if (lastOffset > current.getOffset()) {
				return false;
			}
			
			if (current.getOffset() < 0 || current.getLength() < 0 || current.getOffset() + current.getLength() > maxLength) {
				return false;
			}
			
			lastOffset = current.getOffset() + current.getLength() - 1;
		}

		return true;
	}

	private TextEdit formatClassBodyDeclarations(String source, int indentationLevel, String lineSeparator, IRegion[] regions) {
		ASTNode[] bodyDeclarations = this.codeSnippetParsingUtil.parseClassBodyDeclarations(source.toCharArray(), getDefaultCompilerOptions(), true);
		
		if (bodyDeclarations == null) {
			// a problem occured while parsing the source
			return null;
		}
		return internalFormatClassBodyDeclarations(source, indentationLevel, lineSeparator, bodyDeclarations, regions);
	}

	/**
	 * Returns the resulting text edit after formatting the given comment.
	 * 
	 * @param kind the given kind
	 * @param source the given source
	 * @param indentationLevel the given indentation level
	 * @param lineSeparator the given line separator
	 * @param regions the given regions
	 * @return the resulting text edit
	 * @deprecated
	 */
	private TextEdit formatComment(int kind, String source, int indentationLevel, String lineSeparator, IRegion[] regions) {
		Object oldOption = this.options.get(DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT);
		boolean isFormattingComments = false;
		if (oldOption == null) {
			switch (kind) {
				case CodeFormatter.K_SINGLE_LINE_COMMENT:
					isFormattingComments = DefaultCodeFormatterConstants.TRUE.equals(this.options.get(DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_LINE_COMMENT));
					break;
				case CodeFormatter.K_MULTI_LINE_COMMENT:
					isFormattingComments = DefaultCodeFormatterConstants.TRUE.equals(this.options.get(DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_BLOCK_COMMENT));
					break;
				case CodeFormatter.K_JAVA_DOC:
					isFormattingComments = DefaultCodeFormatterConstants.TRUE.equals(this.options.get(DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_JAVADOC_COMMENT));
			}
		} else {
			isFormattingComments = DefaultCodeFormatterConstants.TRUE.equals(oldOption);
		}
		if (isFormattingComments) {
			if (lineSeparator != null) {
				this.preferences.line_separator = lineSeparator;
			} else {
				this.preferences.line_separator = Util.LINE_SEPARATOR;
			}
			this.preferences.initial_indentation_level = indentationLevel;
			this.newCodeFormatter = new CodeFormatterVisitor(this.preferences, this.options, regions, null);
			
			IRegion coveredRegion = getCoveredRegion(regions);
			int offset = coveredRegion.getOffset();
			int length = coveredRegion.getLength();
			
			final CommentRegion region = createRegion(kind, new Document(source), new Position(offset, length), this.newCodeFormatter);
			if (region != null) {
				return this.newCodeFormatter.format(source, region);
			}
		}
		return new MultiTextEdit();
	}

	private TextEdit formatCompilationUnit(String source, int indentationLevel, String lineSeparator, IRegion[] regions) {
		CompilationUnitDeclaration compilationUnitDeclaration = this.codeSnippetParsingUtil.parseCompilationUnit(source.toCharArray(), getDefaultCompilerOptions(), true);
		
		if (lineSeparator != null) {
			this.preferences.line_separator = lineSeparator;
		} else {
			this.preferences.line_separator = Util.LINE_SEPARATOR;
		}
		this.preferences.initial_indentation_level = indentationLevel;

		this.newCodeFormatter = new CodeFormatterVisitor(this.preferences, this.options, regions, this.codeSnippetParsingUtil);
		
		return this.newCodeFormatter.format(source, compilationUnitDeclaration);
	}

	private TextEdit formatExpression(String source, int indentationLevel, String lineSeparator, IRegion[] regions) {
		Expression expression = this.codeSnippetParsingUtil.parseExpression(source.toCharArray(), getDefaultCompilerOptions(), true);
		
		if (expression == null) {
			// a problem occured while parsing the source
			return null;
		}
		return internalFormatExpression(source, indentationLevel, lineSeparator, expression, regions);
	}

	private TextEdit formatStatements(String source, int indentationLevel, String lineSeparator, IRegion[] regions) {
		ConstructorDeclaration constructorDeclaration = this.codeSnippetParsingUtil.parseStatements(source.toCharArray(), getDefaultCompilerOptions(), true, false);
		
		if (constructorDeclaration.statements == null) {
			// a problem occured while parsing the source
			return null;
		}
		return internalFormatStatements(source, indentationLevel, lineSeparator, constructorDeclaration, regions);
	}

	public String getDebugOutput() {
		return this.newCodeFormatter.scribe.toString();
	}

	private Map getDefaultCompilerOptions() {
		if (this.defaultCompilerOptions ==  null) {
			Map optionsMap = new HashMap(30);
			optionsMap.put(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.DO_NOT_GENERATE); 
			optionsMap.put(CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.DO_NOT_GENERATE);
			optionsMap.put(CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.DO_NOT_GENERATE);
			optionsMap.put(CompilerOptions.OPTION_PreserveUnusedLocal, CompilerOptions.PRESERVE);
			optionsMap.put(CompilerOptions.OPTION_DocCommentSupport, CompilerOptions.DISABLED); 
			optionsMap.put(CompilerOptions.OPTION_ReportMethodWithConstructorName, CompilerOptions.IGNORE); 
			optionsMap.put(CompilerOptions.OPTION_ReportOverridingPackageDefaultMethod, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportOverridingMethodWithoutSuperInvocation, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportDeprecation, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportDeprecationInDeprecatedCode, CompilerOptions.DISABLED); 
			optionsMap.put(CompilerOptions.OPTION_ReportDeprecationWhenOverridingDeprecatedMethod, CompilerOptions.DISABLED); 
			optionsMap.put(CompilerOptions.OPTION_ReportHiddenCatchBlock, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportUnusedLocal, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportUnusedParameter, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportUnusedImport, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportSyntheticAccessEmulation, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportNoEffectAssignment, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportNonExternalizedStringLiteral, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportNoImplicitStringConversion, CompilerOptions.IGNORE); 
			optionsMap.put(CompilerOptions.OPTION_ReportNonStaticAccessToStatic, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportIndirectStaticAccess, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportIncompatibleNonInheritedInterfaceMethod, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportUnusedPrivateMember, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportLocalVariableHiding, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportFieldHiding, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportPossibleAccidentalBooleanAssignment, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportEmptyStatement, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportAssertIdentifier, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportEnumIdentifier, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportUndocumentedEmptyBlock, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportUnnecessaryTypeCheck, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportInvalidJavadoc, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportInvalidJavadocTagsVisibility, CompilerOptions.PUBLIC);
			optionsMap.put(CompilerOptions.OPTION_ReportInvalidJavadocTags, CompilerOptions.DISABLED);
			optionsMap.put(CompilerOptions.OPTION_ReportMissingJavadocTagDescription, CompilerOptions.RETURN_TAG);
			optionsMap.put(CompilerOptions.OPTION_ReportInvalidJavadocTagsDeprecatedRef, CompilerOptions.DISABLED);
			optionsMap.put(CompilerOptions.OPTION_ReportInvalidJavadocTagsNotVisibleRef, CompilerOptions.DISABLED);
			optionsMap.put(CompilerOptions.OPTION_ReportMissingJavadocTags, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportMissingJavadocTagsVisibility, CompilerOptions.PUBLIC);
			optionsMap.put(CompilerOptions.OPTION_ReportMissingJavadocTagsOverriding, CompilerOptions.DISABLED);
			optionsMap.put(CompilerOptions.OPTION_ReportMissingJavadocComments, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportMissingJavadocCommentsVisibility, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportMissingJavadocCommentsOverriding, CompilerOptions.DISABLED);
			optionsMap.put(CompilerOptions.OPTION_ReportFinallyBlockNotCompletingNormally, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportUnusedDeclaredThrownException, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_ReportUnusedDeclaredThrownExceptionWhenOverriding, CompilerOptions.DISABLED); 
			optionsMap.put(CompilerOptions.OPTION_ReportUnqualifiedFieldAccess, CompilerOptions.IGNORE);
			optionsMap.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_1_4);
			optionsMap.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_2); 
			optionsMap.put(CompilerOptions.OPTION_TaskTags, ""); //$NON-NLS-1$
			optionsMap.put(CompilerOptions.OPTION_TaskPriorities, ""); //$NON-NLS-1$
			optionsMap.put(CompilerOptions.OPTION_TaskCaseSensitive, CompilerOptions.DISABLED);
			optionsMap.put(CompilerOptions.OPTION_ReportUnusedParameterWhenImplementingAbstract, CompilerOptions.DISABLED); 
			optionsMap.put(CompilerOptions.OPTION_ReportUnusedParameterWhenOverridingConcrete, CompilerOptions.DISABLED); 
			optionsMap.put(CompilerOptions.OPTION_ReportSpecialParameterHidingField, CompilerOptions.DISABLED); 
			optionsMap.put(CompilerOptions.OPTION_MaxProblemPerUnit, String.valueOf(100));
			optionsMap.put(CompilerOptions.OPTION_InlineJsr, CompilerOptions.DISABLED); 
			this.defaultCompilerOptions = optionsMap;
		}
		Object sourceOption = this.options.get(CompilerOptions.OPTION_Source);
		if (sourceOption != null) {
			this.defaultCompilerOptions.put(CompilerOptions.OPTION_Source, sourceOption);
		} else {
			this.defaultCompilerOptions.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_3);
		}
		return this.defaultCompilerOptions;		
	}

	private TextEdit internalFormatClassBodyDeclarations(String source, int indentationLevel, String lineSeparator, ASTNode[] bodyDeclarations, IRegion[] regions) {
		if (lineSeparator != null) {
			this.preferences.line_separator = lineSeparator;
		} else {
			this.preferences.line_separator = Util.LINE_SEPARATOR;
		}
		this.preferences.initial_indentation_level = indentationLevel;

		this.newCodeFormatter = new CodeFormatterVisitor(this.preferences, this.options, regions, this.codeSnippetParsingUtil);
		return this.newCodeFormatter.format(source, bodyDeclarations);
	}
	
	private TextEdit internalFormatExpression(String source, int indentationLevel, String lineSeparator, Expression expression, IRegion[] regions) {
		if (lineSeparator != null) {
			this.preferences.line_separator = lineSeparator;
		} else {
			this.preferences.line_separator = Util.LINE_SEPARATOR;
		}
		this.preferences.initial_indentation_level = indentationLevel;

		this.newCodeFormatter = new CodeFormatterVisitor(this.preferences, this.options, regions, this.codeSnippetParsingUtil);
		
		TextEdit textEdit = this.newCodeFormatter.format(source, expression);
		return textEdit;
	}

	private TextEdit internalFormatStatements(String source, int indentationLevel, String lineSeparator, ConstructorDeclaration constructorDeclaration, IRegion[] regions) {
		if (lineSeparator != null) {
			this.preferences.line_separator = lineSeparator;
		} else {
			this.preferences.line_separator = Util.LINE_SEPARATOR;
		}
		this.preferences.initial_indentation_level = indentationLevel;

		this.newCodeFormatter = new CodeFormatterVisitor(this.preferences, this.options, regions, this.codeSnippetParsingUtil);
		
		return this.newCodeFormatter.format(source, constructorDeclaration);
	}

	private TextEdit probeFormatting(String source, int indentationLevel, String lineSeparator, IRegion[] regions) {
		if (ProbingScanner == null) {
			// scanner use to check if the kind could be K_JAVA_DOC, K_MULTI_LINE_COMMENT or K_SINGLE_LINE_COMMENT 
			ProbingScanner = new Scanner(true, true, false/*nls*/, ClassFileConstants.JDK1_3, ClassFileConstants.JDK1_3, null/*taskTags*/, null/*taskPriorities*/, true/*taskCaseSensitive*/);
		}
		ProbingScanner.setSource(source.toCharArray());
		
		IRegion coveredRegion = getCoveredRegion(regions);
		int offset = coveredRegion.getOffset();
		int length = coveredRegion.getLength();
		
		ProbingScanner.resetTo(offset, offset + length);
		try {
			switch(ProbingScanner.getNextToken()) {
				case ITerminalSymbols.TokenNameCOMMENT_BLOCK :
					if (ProbingScanner.getCurrentTokenEndPosition() == offset + length - 1) {
						return formatComment(K_MULTI_LINE_COMMENT, source, indentationLevel, lineSeparator, regions);
					}
					break;
				case ITerminalSymbols.TokenNameCOMMENT_LINE :
					if (ProbingScanner.getCurrentTokenEndPosition() == offset + length - 1) {
						return formatComment(K_SINGLE_LINE_COMMENT, source, indentationLevel, lineSeparator, regions);
					}
					break;
				case ITerminalSymbols.TokenNameCOMMENT_JAVADOC :
					if (ProbingScanner.getCurrentTokenEndPosition() == offset + length - 1) {
						return formatComment(K_JAVA_DOC, source, indentationLevel, lineSeparator, regions);
					}
			}
		} catch (InvalidInputException e) {
			// ignore
		}
		ProbingScanner.setSource((char[]) null);

		// probe for expression
		Expression expression = this.codeSnippetParsingUtil.parseExpression(source.toCharArray(), getDefaultCompilerOptions(), true);
		if (expression != null) {
			return internalFormatExpression(source, indentationLevel, lineSeparator, expression, regions);
		}

		// probe for body declarations (fields, methods, constructors)
		ASTNode[] bodyDeclarations = this.codeSnippetParsingUtil.parseClassBodyDeclarations(source.toCharArray(), getDefaultCompilerOptions(), true);
		if (bodyDeclarations != null) {
			return internalFormatClassBodyDeclarations(source, indentationLevel, lineSeparator, bodyDeclarations, regions);
		}

		// probe for statements
		ConstructorDeclaration constructorDeclaration = this.codeSnippetParsingUtil.parseStatements(source.toCharArray(), getDefaultCompilerOptions(), true, false);
		if (constructorDeclaration.statements != null) {
			return internalFormatStatements(source, indentationLevel, lineSeparator, constructorDeclaration, regions);
		}

		// this has to be a compilation unit
		return formatCompilationUnit(source, indentationLevel, lineSeparator, regions);
	}
	
	private IRegion getCoveredRegion(IRegion[] regions) {
		int length = regions.length;
		if (length == 1) {
			return regions[0];
		}
		
		int offset = regions[0].getOffset();
		IRegion lastRegion = regions[length - 1];
		
		return new Region(offset, lastRegion.getOffset() + lastRegion.getLength() - offset);
	}
}
