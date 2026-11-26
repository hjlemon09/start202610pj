package com.sapcommerce.occresponse;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;

import java.util.HashMap;
import java.util.Map;

/** Status codes written as HttpStatus.X, HttpServletResponse.SC_X, numbers or constants, shown as "201 CREATED". */
final class HttpStatuses {
    private static final Map<String, Integer> CODES = new HashMap<>();
    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        Object[][] table = {
                {100, "CONTINUE"}, {200, "OK"}, {201, "CREATED"}, {202, "ACCEPTED"}, {203, "NON_AUTHORITATIVE_INFORMATION"},
                {204, "NO_CONTENT"}, {205, "RESET_CONTENT"}, {206, "PARTIAL_CONTENT"}, {207, "MULTI_STATUS"},
                {300, "MULTIPLE_CHOICES"}, {301, "MOVED_PERMANENTLY"}, {302, "FOUND"}, {303, "SEE_OTHER"},
                {304, "NOT_MODIFIED"}, {307, "TEMPORARY_REDIRECT"}, {308, "PERMANENT_REDIRECT"}, {400, "BAD_REQUEST"},
                {401, "UNAUTHORIZED"}, {402, "PAYMENT_REQUIRED"}, {403, "FORBIDDEN"}, {404, "NOT_FOUND"},
                {405, "METHOD_NOT_ALLOWED"}, {406, "NOT_ACCEPTABLE"}, {408, "REQUEST_TIMEOUT"}, {409, "CONFLICT"},
                {410, "GONE"}, {411, "LENGTH_REQUIRED"}, {412, "PRECONDITION_FAILED"}, {413, "PAYLOAD_TOO_LARGE"},
                {415, "UNSUPPORTED_MEDIA_TYPE"}, {422, "UNPROCESSABLE_ENTITY"}, {423, "LOCKED"}, {428, "PRECONDITION_REQUIRED"},
                {429, "TOO_MANY_REQUESTS"}, {500, "INTERNAL_SERVER_ERROR"}, {501, "NOT_IMPLEMENTED"}, {502, "BAD_GATEWAY"},
                {503, "SERVICE_UNAVAILABLE"}, {504, "GATEWAY_TIMEOUT"}};
        for (Object[] row : table) {
            CODES.put((String) row[1], (Integer) row[0]);
            NAMES.put((Integer) row[0], (String) row[1]);
        }
        // HttpServletResponse constants that are named differently from HttpStatus
        CODES.put("MOVED_TEMPORARILY", 302);
        CODES.put("REQUEST_ENTITY_TOO_LARGE", 413);
        CODES.put("NON_AUTHORITATIVE_INFORMATION", 203);
    }

    private HttpStatuses() {
    }

    /** "201 CREATED" from a name (CREATED, SC_CREATED) or a number; the text itself when unknown. */
    static ResponseScanner.Status of(String value, String kind) {
        String v = value.trim();
        if (v.startsWith("SC_")) v = v.substring(3);
        Integer code = CODES.get(v);
        if (code != null) return new ResponseScanner.Status(code + " " + NAMES.getOrDefault(code, v), kind);
        try {
            int n = Integer.parseInt(v);
            return new ResponseScanner.Status(n + (NAMES.containsKey(n) ? " " + NAMES.get(n) : ""), kind);
        } catch (NumberFormatException e) {
            return new ResponseScanner.Status(value, kind);
        }
    }

    static ResponseScanner.Status resolve(ExpressionTree e, Expressions expressions, JavaSources.TypeRef context) {
        if (e instanceof LiteralTree l && l.getValue() instanceof Number n) return of(String.valueOf(n.intValue()), "LITERAL");
        if (e instanceof MemberSelectTree ms) {
            String owner = JavaSources.simple(ms.getExpression().toString());
            String id = ms.getIdentifier().toString();
            if (owner.equals("HttpStatus") || owner.equals("HttpServletResponse") || owner.equals("HttpStatusCode"))
                return of(id, "ENUM");
        }
        if (e instanceof MethodInvocationTree call && call.getArguments().size() == 1
                && call.getMethodSelect() instanceof MemberSelectTree ms
                && (ms.getIdentifier().contentEquals("valueOf") || ms.getIdentifier().contentEquals("value"))) {
            ResponseScanner.Status inner = resolve(Expressions.strip(call.getArguments().get(0)), expressions, context);
            if (!inner.kind().equals("DYNAMIC")) return inner;
        }
        if (e instanceof MethodInvocationTree call && call.getArguments().isEmpty()
                && call.getMethodSelect() instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("value"))
            return resolve(Expressions.strip(ms.getExpression()), expressions, context);
        String constant = expressions.constant(context, e, 0);
        if (constant != null) return of(constant, "CONSTANT");
        if (e instanceof IdentifierTree || e instanceof MemberSelectTree) {
            // static import of HttpStatus.CREATED / SC_CREATED
            String id = e instanceof IdentifierTree i ? i.getName().toString() : ((MemberSelectTree) e).getIdentifier().toString();
            String key = id.startsWith("SC_") ? id.substring(3) : id;
            if (CODES.containsKey(key)) return of(id, "ENUM");
        }
        return new ResponseScanner.Status(Expressions.text(e), "DYNAMIC");
    }
}
