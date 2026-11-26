package com.sapcommerce.occresponse;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Constant values, attribute names and value types of expressions, as far as the sources tell. */
final class Expressions {
    /** Attribute name: kind is LITERAL, CONSTANT, TYPE_DERIVED, DYNAMIC, UNRESOLVED_CONSTANT or BULK. */
    record Name(String kind, String value, String expression) {
    }

    /** Where an expression is evaluated: a method body, and its node in the call graph of one endpoint. */
    record Scope(JavaSources.MethodRef method, String endpointId, String node) {
    }

    private static final Set<String> COLLECTIONS = Set.of("List", "ArrayList", "LinkedList", "Set", "HashSet",
            "LinkedHashSet", "TreeSet", "SortedSet", "Collection", "Iterable");

    private final JavaSources sources;
    private final CallGraph graph;
    private final Map<MethodTree, Map<String, List<VariableTree>>> locals = new HashMap<>();

    Expressions(JavaSources sources, CallGraph graph) {
        this.sources = sources;
        this.graph = graph;
    }

    // ---------------------------------------------------------------- names

    Name name(Scope scope, ExpressionTree e) {
        String text = text(e);
        if (e instanceof LiteralTree l && l.getValue() instanceof String s) return new Name("LITERAL", s, text);
        String constant = constant(scope.method().owner(), e, 0);
        if (constant != null) return new Name("CONSTANT", constant, text);
        ExpressionTree inner = strip(e);
        if (inner instanceof IdentifierTree id) {
            VariableTree v = local(scope, id.getName().toString(), scope.method().unit().line(id));
            if (v != null) { // a local holding a constant, e.g. final String key = WebConstants.X;
                String value = constant(scope.method().owner(), v.getInitializer(), 0);
                return value != null ? new Name("CONSTANT", value, text) : new Name("DYNAMIC", "", text);
            }
            return new Name("UNRESOLVED_CONSTANT", "", text);
        }
        if (inner instanceof MemberSelectTree ms && looksLikeConstant(ms)) return new Name("UNRESOLVED_CONSTANT", "", text);
        return new Name("DYNAMIC", "", text);
    }

    /** Name Spring derives from the value type (Conventions.getVariableName): decapitalized short name, collections as xxxList. */
    static Name derived(String type, String expression) {
        String raw = JavaSources.simple(type);
        if (type == null || type.isBlank() || raw.isEmpty() || raw.equals("Object") || raw.equals("var"))
            return new Name("TYPE_DERIVED", "", expression);
        String element = raw;
        boolean plural = type.trim().endsWith("[]");
        if (COLLECTIONS.contains(raw)) {
            int lt = type.indexOf('<'), gt = type.lastIndexOf('>');
            if (lt < 0 || gt <= lt + 1) return new Name("TYPE_DERIVED", "", expression);
            element = JavaSources.simple(type.substring(lt + 1, gt).replace("? extends ", ""));
            plural = true;
        }
        String name = decapitalize(element) + (plural ? "List" : "");
        return new Name("TYPE_DERIVED", name, expression);
    }

    static String decapitalize(String s) {
        if (s.isEmpty()) return s;
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean looksLikeConstant(MemberSelectTree ms) {
        String id = ms.getIdentifier().toString();
        return id.equals(id.toUpperCase()) && !(ms.getExpression() instanceof MethodInvocationTree);
    }

    // ---------------------------------------------------------------- constants

    /** String value of a compile-time constant expression (literals, constant fields, concatenation), or null. */
    String constant(JavaSources.TypeRef context, ExpressionTree e, int depth) {
        if (e == null || depth > 12) return null;
        e = strip(e);
        if (e instanceof LiteralTree l) return l.getValue() == null ? null : String.valueOf(l.getValue());
        if (e instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS) {
            String left = constant(context, b.getLeftOperand(), depth + 1);
            String right = left == null ? null : constant(context, b.getRightOperand(), depth + 1);
            return right == null ? null : left + right;
        }
        JavaSources.FieldRef f = null;
        if (e instanceof IdentifierTree id) f = sources.visibleField(context, id.getName().toString());
        else if (e instanceof MemberSelectTree ms) {
            JavaSources.TypeRef owner = sources.resolve(context, ms.getExpression().toString());
            if (owner != null) f = sources.field(owner, ms.getIdentifier().toString());
        }
        if (f == null || f.field().getInitializer() == null) return null;
        return constant(f.owner(), f.field().getInitializer(), depth + 1);
    }

    // ---------------------------------------------------------------- types

    /** Declared or inferred type text of an expression, "" when unknown. */
    String type(Scope scope, ExpressionTree e, int depth) {
        if (e == null || depth > 6) return "";
        JavaSources.MethodRef m = scope.method();
        JavaSources.TypeRef owner = m.owner();
        e = e instanceof ParenthesizedTree p ? p.getExpression() : e;
        switch (e) {
            case LiteralTree l -> {
                return switch (l.getKind()) {
                    case STRING_LITERAL -> "String";
                    case INT_LITERAL -> "int";
                    case LONG_LITERAL -> "long";
                    case BOOLEAN_LITERAL -> "boolean";
                    case CHAR_LITERAL -> "char";
                    case DOUBLE_LITERAL -> "double";
                    case FLOAT_LITERAL -> "float";
                    default -> "";
                };
            }
            case NewClassTree n -> {
                return n.getIdentifier().toString();
            }
            case NewArrayTree n -> {
                return n.getType() == null ? "" : n.getType() + "[]";
            }
            case TypeCastTree c -> {
                return c.getType().toString();
            }
            case ConditionalExpressionTree c -> {
                String t = type(scope, c.getTrueExpression(), depth + 1);
                return t.isEmpty() || t.equals("null") ? type(scope, c.getFalseExpression(), depth + 1) : t;
            }
            case BinaryTree b -> {
                if (b.getKind() == Tree.Kind.PLUS) {
                    String l = type(scope, b.getLeftOperand(), depth + 1), r = type(scope, b.getRightOperand(), depth + 1);
                    if (l.equals("String") || r.equals("String")) return "String";
                    return l;
                }
                return switch (b.getKind()) {
                    case EQUAL_TO, NOT_EQUAL_TO, LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL, GREATER_THAN_EQUAL,
                         CONDITIONAL_AND, CONDITIONAL_OR -> "boolean";
                    default -> type(scope, b.getLeftOperand(), depth + 1);
                };
            }
            case IdentifierTree id -> {
                String name = id.getName().toString();
                VariableTree v = local(scope, name, m.unit().line(id));
                if (v != null) return v.getType() == null || v.getType().toString().equals("var")
                        ? type(scope, v.getInitializer(), depth + 1) : v.getType().toString();
                JavaSources.FieldRef f = sources.visibleField(owner, name);
                return f == null ? "" : f.field().getType().toString();
            }
            case MemberSelectTree ms -> {
                String qualifier = ms.getExpression().toString();
                if (qualifier.equals("this") || qualifier.equals("super")) {
                    JavaSources.FieldRef f = sources.field(owner, ms.getIdentifier().toString());
                    return f == null ? "" : f.field().getType().toString();
                }
                JavaSources.TypeRef t = sources.resolve(owner, qualifier);
                if (t == null) {
                    String qt = type(scope, ms.getExpression(), depth + 1);
                    t = qt.isEmpty() ? null : sources.resolve(owner, qt);
                }
                JavaSources.FieldRef f = t == null ? null : sources.field(t, ms.getIdentifier().toString());
                return f == null ? "" : f.field().getType().toString();
            }
            case MethodInvocationTree call -> {
                return returnType(scope, call, depth);
            }
            default -> {
                return "";
            }
        }
    }

    private String returnType(Scope scope, MethodInvocationTree call, int depth) {
        JavaSources.MethodRef m = scope.method();
        String name = methodName(call);
        int argc = call.getArguments().size();
        Set<String> types = new LinkedHashSet<>();
        for (CallGraph.Edge edge : graph.calls(scope.endpointId(), scope.node(), name, argc, m.unit().line(call))) {
            JavaSources.MethodRef callee = sources.findMethod(edge.calleeFile(), edge.calleeClass(), edge.calleeMethod(), edge.calleeLine());
            if (callee != null && callee.method().getReturnType() != null) types.add(callee.method().getReturnType().toString());
        }
        if (!types.isEmpty()) return String.join(" | ", types);
        // Not in the call graph (getters of data/model classes are left out there): look the method up by receiver type.
        JavaSources.TypeRef receiver;
        if (call.getMethodSelect() instanceof MemberSelectTree ms) {
            String q = ms.getExpression().toString();
            receiver = q.equals("this") || q.equals("super") ? m.owner() : sources.resolve(m.owner(), q);
            if (receiver == null || ms.getExpression() instanceof MethodInvocationTree) {
                String rt = type(scope, ms.getExpression(), depth + 1);
                receiver = rt.isEmpty() ? null : sources.resolve(m.owner(), rt.split(" \\| ")[0]);
            }
        } else receiver = m.owner();
        JavaSources.MethodRef target = receiver == null ? null : sources.method(receiver, name, argc);
        return target == null || target.method().getReturnType() == null ? "" : target.method().getReturnType().toString();
    }

    static String methodName(MethodInvocationTree call) {
        return call.getMethodSelect() instanceof MemberSelectTree ms ? ms.getIdentifier().toString()
                : call.getMethodSelect().toString();
    }

    /** The local variable or parameter named so, declared last before the line. */
    VariableTree local(Scope scope, String name, int line) {
        JavaSources.MethodRef m = scope.method();
        Map<String, List<VariableTree>> byName = locals.computeIfAbsent(m.method(), k -> {
            Map<String, List<VariableTree>> out = new HashMap<>();
            for (VariableTree p : k.getParameters()) out.computeIfAbsent(p.getName().toString(), x -> new ArrayList<>()).add(p);
            if (k.getBody() != null) new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree v, Void unused) {
                    out.computeIfAbsent(v.getName().toString(), x -> new ArrayList<>()).add(v);
                    return super.visitVariable(v, unused);
                }

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree l, Void unused) {
                    return super.visitLambdaExpression(l, unused);
                }
            }.scan(k.getBody(), null);
            return out;
        });
        VariableTree best = null;
        int bestLine = -1;
        for (VariableTree v : byName.getOrDefault(name, List.of())) {
            int l = m.unit().line(v);
            if (l <= line && l >= bestLine) { best = v; bestLine = l; }
        }
        return best;
    }

    static ExpressionTree strip(ExpressionTree e) {
        while (true) {
            if (e instanceof ParenthesizedTree p) e = p.getExpression();
            else if (e instanceof TypeCastTree c) e = c.getExpression();
            else return e;
        }
    }

    static String text(Tree t) {
        return t == null ? "" : t.toString().replaceAll("\\s+", " ").trim();
    }
}
