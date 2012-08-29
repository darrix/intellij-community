package com.jetbrains.python.psi.types;

import com.google.common.base.CharMatcher;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.documentation.DocStringUtil;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyImportedModule;
import com.jetbrains.python.psi.impl.PyQualifiedName;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import com.jetbrains.python.psi.stubs.PyClassNameIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author yole
 */
public class PyTypeParser {
  private static final Pattern PARAMETRIZED_CLASS = Pattern.compile("([a-zA-Z0-9._]+) of (.+)");
  private static final Pattern DICT_TYPE = Pattern.compile("dict from (.+) to (.+)");

  private PyTypeParser() {
  }

  @Nullable
  public static PyType getTypeByName(@Nullable PsiElement anchor, @NotNull String type) {
    final ParseResult result = parse(anchor, type);
    return result.getType();
  }

  @NotNull
  public static ParseResult parse(@Nullable PsiElement anchor, @NotNull String type) {
    final Map<TextRange, PyType> types = new HashMap<TextRange, PyType>();
    final Map<PyType, TextRange> fullRanges = new HashMap<PyType, TextRange>();
    final Map<PyType, PyImportElement> imports = new HashMap<PyType, PyImportElement>();
    final PyType t = parse(anchor, type, types, fullRanges, imports, 0);
    return new ParseResult(t, types, fullRanges, imports);
  }

  public static class ParseResult {
    @Nullable private PyType myType;
    @NotNull private Map<TextRange, PyType> myTypes;
    @NotNull private Map<PyType, TextRange> myFullRanges;
    @NotNull private final Map<PyType, PyImportElement> myImports;

    ParseResult(@Nullable PyType type, @NotNull Map<TextRange, PyType> types, @NotNull Map<PyType, TextRange> fullRanges,
                @NotNull Map<PyType, PyImportElement> imports) {
      myType = type;
      myTypes = types;
      myFullRanges = fullRanges;
      myImports = imports;
    }

    @Nullable
    public PyType getType() {
      return myType;
    }

    @NotNull
    public Map<TextRange, PyType> getTypes() {
      return myTypes;
    }

    @NotNull
    public Map<PyType, TextRange> getFullRanges() {
      return myFullRanges;
    }

    @NotNull
    public Map<PyType, PyImportElement> getImports() {
      return myImports;
    }
  }

  @Nullable
  private static PyType parse(@Nullable PsiElement anchor, @NotNull String type, @NotNull Map<TextRange, PyType> types,
                              @NotNull Map<PyType, TextRange> fullRanges, @NotNull Map<PyType, PyImportElement> imports, int offset) {
    if (anchor == null || !anchor.isValid()) {
      return null;
    }
    final String trimmed = DocStringUtil.trimDocString(type);
    offset += type.indexOf(trimmed);
    type = trimmed;
    final TextRange whole = new TextRange(offset, offset + type.length());
    if (type.equals("None")) {
      final PyType t = PyNoneType.INSTANCE;
      types.put(whole, t);
      return t;
    }
    if (type.startsWith("(") && type.endsWith(")")) {
      return parseTupleType(anchor, type.substring(1, type.length() - 1), types, fullRanges, imports, offset + 1);
    }
    if (type.contains(" or ")) {
      return parseUnionType(anchor, type, types, fullRanges, imports, offset);
    }
    final PyBuiltinCache builtinCache = PyBuiltinCache.getInstance(anchor);
    if (type.equals("unknown")) {
      return null;
    }
    if (type.equals("string")) {
      PyType t = builtinCache.getStringType(LanguageLevel.forElement(anchor));
      types.put(whole, t);
      return t;
    }
    if (type.equals("str")) {
      final PyType t = builtinCache.getStrType();
      types.put(whole, t);
      return t;
    }
    if (type.equals("bytes")) {
      final PyType t = builtinCache.getBytesType(LanguageLevel.forElement(anchor));
      types.put(whole, t);
      return t;
    }
    if (type.equals("unicode")) {
      final PyType t = builtinCache.getUnicodeType(LanguageLevel.forElement(anchor));
      types.put(whole, t);
      return t;
    }
    if (type.equals("boolean")) {
      final PyType t = builtinCache.getBoolType();
      types.put(whole, t);
      return t;
    }
    if (type.equals("file object")) {
      final PyType t = builtinCache.getObjectType("file");
      types.put(whole, t);
      return t;
    }
    if (type.equals("dictionary")) {
      final PyType t = builtinCache.getObjectType("dict");
      types.put(whole, t);
      return t;
    }
    if (type.startsWith("dict from")) {
      return parseDictFromToType(anchor, type, types, fullRanges, imports, offset);
    }
    if (type.equals("integer") || (type.equals("long") && LanguageLevel.forElement(anchor).isPy3K())) {
      final PyType t = builtinCache.getIntType();
      types.put(whole, t);
      return t;
    }
    if (type.length() == 1 && 'T' <= type.charAt(0) && type.charAt(0) <= 'Z') {
      return new PyGenericType(type);
    }
    final Matcher m = PARAMETRIZED_CLASS.matcher(type);
    if (m.matches()) {
      final PyType objType = parseObjectType(anchor, m.group(1), builtinCache, types, fullRanges, imports, offset + m.start(1));
      final PyType elementType = parse(anchor, m.group(2), types, fullRanges, imports, offset + m.start(2));
      if (objType != null) {
        if (objType instanceof PyClassType && elementType != null) {
          return new PyCollectionTypeImpl(((PyClassType)objType).getPyClass(), false, elementType);
        }
        return objType;
      }
    }
    return parseObjectType(anchor, type, builtinCache, types, fullRanges, imports, offset);
  }

  @Nullable
  private static PyType parseObjectType(@NotNull PsiElement anchor, @NotNull String type, @NotNull PyBuiltinCache builtinCache,
                                        @NotNull Map<TextRange, PyType> types, @NotNull Map<PyType, TextRange> fullRanges,
                                        @NotNull Map<PyType, PyImportElement> imports, int offset) {
    final TextRange whole = new TextRange(offset, offset + type.length());
    final PyClassType classType = builtinCache.getObjectType(type);
    if (classType != null) {
      types.put(whole, classType);
      return classType;
    }
    final PsiFile anchorFile = anchor.getContainingFile();
    if (anchorFile instanceof PyFile) {
      final PyClass aClass = ((PyFile)anchorFile).findTopLevelClass(type);
      if (aClass != null) {
        final PyType t = new PyClassTypeImpl(aClass, false);
        t.assertValid("PyFile.findTopLevelClass()");
        types.put(whole, t);
        return t;
      }

      final List<PyFromImportStatement> fromImports = ((PyFile)anchorFile).getFromImports();
      for (PyFromImportStatement fromImportStatement : fromImports) {
        final PyImportElement[] elements = fromImportStatement.getImportElements();
        for (PyImportElement element : elements) {
          final String visibleName = element.getVisibleName();
          if (type.equals(visibleName)) {
            final PyQualifiedName importSourceQName = fromImportStatement.getImportSourceQName();
            if (importSourceQName != null) {
              String qName = importSourceQName.toString() + "." + visibleName;
              final HashMap<TextRange, PyType> unusedRanges = new HashMap<TextRange, PyType>();
              final PyType pyType = getTypeFromQName(anchor, qName, whole, unusedRanges, fullRanges, offset);
              if (pyType != null) {
                types.put(whole, pyType);
                imports.put(pyType, element);
                return pyType;
              }
            }
          }
        }
      }

    }
    if (StringUtil.isJavaIdentifier(type)) {
      final Collection<PyClass> classes = PyClassNameIndex.find(type, anchor.getProject(), true);
      if (classes.size() == 1) {
        final PyType t = new PyClassTypeImpl(classes.iterator().next(), false);
        t.assertValid("PyClassNameIndex.find().iterator().next()");
        types.put(whole, t);
        return t;
      }
    }
    return getTypeFromQName(anchor, type, whole, types, fullRanges, offset);
  }

  @Nullable
  public static PyType getTypeFromQName(@NotNull PsiElement anchor, @NotNull String qName, TextRange whole,
                                        @NotNull Map<TextRange, PyType> types, @NotNull Map<PyType,
                                        TextRange> fullRanges, int offset) {
    final PsiFile anchorFile = anchor.getContainingFile();
    if (CharMatcher.JAVA_LETTER_OR_DIGIT.or(CharMatcher.is('.')).or(CharMatcher.is('_')).matchesAllOf(qName)) {
      final List<TextRange> ranges = splitRanges(qName, ".");
      final TextRange classRange = !ranges.isEmpty() ? ranges.remove(ranges.size() - 1) : new TextRange(0, qName.length());
      PyType moduleType = null;
      if (!ranges.isEmpty()) {
        final TextRange first = ranges.get(0);
        for (TextRange range : ranges) {
          final PyQualifiedName moduleName = PyQualifiedName.fromDottedString(first.union(range).substring(qName));
          if (anchorFile instanceof PyFile) {
            moduleType = new PyImportedModuleType(new PyImportedModule(null, (PyFile)anchorFile, moduleName));
            types.put(range.shiftRight(offset), moduleType);
          }
        }
      }
      final String shortName = classRange.substring(qName);
      if (moduleType != null) {
        final PyResolveContext context = PyResolveContext.defaultContext();
        final List<? extends RatedResolveResult> results = moduleType.resolveMember(shortName, null, AccessDirection.READ, context);
        if (results != null && !results.isEmpty()) {
          final RatedResolveResult result = results.get(0);
          final PsiElement resolved = result.getElement();
          if (resolved instanceof PyTypedElement) {
            PyType t = ((PyTypedElement)resolved).getType(context.getTypeEvalContext());
            if (t instanceof PyClassType) {
              t = ((PyClassType)t).toInstance();
            }
            if (t != null) {
              t.assertValid("PyImportedModuleType.resolveMember()");
              types.put(classRange.shiftRight(offset), t);
              fullRanges.put(t, whole);
              return t;
            }
          }
        }
      }
      final Collection<PyClass> classes = PyClassNameIndex.find(shortName, anchor.getProject(), true);
      for (PyClass aClass : classes) {
        if (qName.equals(aClass.getQualifiedName())) {
          final PyType t = new PyClassTypeImpl(aClass, false);
          t.assertValid("PyClassNameIndex.find()");
          types.put(classRange.shiftRight(offset), t);
          fullRanges.put(t, whole);
          return t;
        }
      }
      // Workaround for stdlib modules _abcoll, _functools, etc.
      for (PyClass aClass : classes) {
        final String name = aClass.getQualifiedName();
        if (name != null && name.startsWith("_")) {
          final PyType t = new PyClassTypeImpl(aClass, false);
          t.assertValid("PyClassNameIndex.find() for _foo");
          types.put(classRange.shiftRight(offset), t);
          fullRanges.put(t, whole);
          return t;
        }
      }
    }
    return null;
  }

  @Nullable
  private static PyType parseTupleType(@NotNull PsiElement anchor, @NotNull String elementTypeNames, @NotNull Map<TextRange, PyType> types,
                                       @NotNull Map<PyType, TextRange> fullRanges, @NotNull Map<PyType, PyImportElement> imports,
                                       int offset) {
    final List<TextRange> ranges = splitRanges(elementTypeNames, ",");
    final List<PyType> elementTypes = new ArrayList<PyType>();
    for (TextRange range : ranges) {
      elementTypes.add(parse(anchor, range.substring(elementTypeNames), types, fullRanges, imports, offset + range.getStartOffset()));
    }
    return PyTupleType.create(anchor, elementTypes.toArray(new PyType[elementTypes.size()]));
  }

  @Nullable
  private static PyType parseDictFromToType(@NotNull PsiElement anchor, @NotNull String type, @NotNull Map<TextRange, PyType> types,
                                            @NotNull Map<PyType, TextRange> fullRanges, @NotNull Map<PyType, PyImportElement> imports,
                                            int offset) {
    final Matcher m = DICT_TYPE.matcher(type);
    PyClassType dict = PyBuiltinCache.getInstance(anchor).getDictType();
    if (dict != null) {
      if (m.matches()) {
        PyType from = parse(anchor, m.group(1), types, fullRanges, imports, offset + m.start(1));
        PyType to = parse(anchor, m.group(2), types, fullRanges, imports, offset + m.start(2));
        final PyType p = PyTupleType.create(anchor, new PyType[] {from, to});
        return new PyCollectionTypeImpl(dict.getPyClass(), false, p);
      }
      return dict;
    }
    return null;
  }

  @Nullable
  private static PyType parseUnionType(@NotNull PsiElement anchor, @NotNull String type, @NotNull Map<TextRange, PyType> types,
                                       @NotNull Map<PyType, TextRange> fullRanges, @NotNull Map<PyType, PyImportElement> imports,
                                       int offset) {
    final List<TextRange> ranges = splitRanges(type, " or ");
    PyType result = null;
    for (TextRange range : ranges) {
      final PyType t = parse(anchor, range.substring(type), types, fullRanges, imports, offset + range.getStartOffset());
      result = (result == null) ? t : PyUnionType.union(result, t);
    }
    return result;
  }

  @NotNull
  private static List<TextRange> splitRanges(@NotNull final String s, @NotNull final String separator) {
    List<TextRange> ranges = new ArrayList<TextRange>();
    final int len = s.length();
    final int seplen = separator.length();
    int end;
    for (int start = 0; start < len; start = end + seplen) {
      final int n = s.indexOf(separator, start);
      end = (n == -1) ? len : n;
      ranges.add(new TextRange(start, end));
    }
    return ranges;
  }
}
