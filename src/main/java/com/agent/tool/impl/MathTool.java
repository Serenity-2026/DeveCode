package com.agent.tool.impl;

import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.result.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 数学表达式计算工具。
 *
 * 支持的运算符和函数：
 *   + - * / % ^（幂运算）
 *   sqrt(x)  sin(x)  cos(x)  tan(x)  log(x)  ln(x)  abs(x)  round(x)
 *   常量：pi, e
 *
 * 安全性：使用手写递归下降解析器，不依赖 ScriptEngine，无法执行任意代码。
 */
public class MathTool implements Tool {

    private static final String DESCRIPTION = """
            Evaluate a mathematical expression and return the result.

            Supported operators: +  -  *  /  %  ^ (power)
            Supported functions: sqrt(x)  sin(x)  cos(x)  tan(x)  log(x)  ln(x)  abs(x)  round(x)
            Constants: pi  e

            Examples:
              "2 + 3 * 4"          → 14
              "sqrt(144)"           → 12
              "sin(pi / 2)"         → 1
              "2^10"                → 1024
              "log(1000)"           → 3 (base 10)
              "ln(e^3)"             → 3 (natural log)

            Use parentheses to control order of operations.""";

    @Override
    public String name() {
        return "Math";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "expression", Map.of(
                                        "type", "string",
                                        "description", "Mathematical expression to evaluate, e.g. \"sqrt(144) + 2^3\"")
                        ),
                        "required", List.of("expression")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String expr = stringArg(args, "expression", "");
        if (expr.isEmpty()) {
            return ToolResult.error("Error: expression is required");
        }
        try {
            double result = new ExprParser(expr).parse();
            // 整数结果去掉小数部分
            String formatted;
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                formatted = String.valueOf((long) result);
            } else {
                formatted = String.format("%.10g", result);
            }
            return ToolResult.success(expr.trim() + " = " + formatted);
        } catch (Exception e) {
            return ToolResult.error("Error evaluating expression: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  递归下降解析器
    // ═══════════════════════════════════════════════════════════════

    /**
     * 手写递归下降解析器，支持 + - * / % ^ 和常见数学函数。
     *
     * 文法：
     *   expr   → term (('+' | '-') term)*
     *   term   → factor (('*' | '/' | '%') factor)*
     *   factor → power ('^' factor)?           // 右结合
     *   power  → number | const | func '(' expr ')' | '(' expr ')' | '-' power
     */
    private static class ExprParser {
        private final String input;
        private int pos = 0;

        ExprParser(String input) {
            this.input = input;
        }

        double parse() {
            double result = expr();
            skipSpaces();
            if (pos < input.length()) {
                throw new RuntimeException("Unexpected character at position " + pos + ": '" + input.charAt(pos) + "'");
            }
            return result;
        }

        private double expr() {
            double left = term();
            while (true) {
                skipSpaces();
                if (match('+')) { left += term(); }
                else if (match('-')) { left -= term(); }
                else break;
            }
            return left;
        }

        private double term() {
            double left = factor();
            while (true) {
                skipSpaces();
                if (match('*')) { left *= factor(); }
                else if (match('/')) {
                    double d = factor();
                    if (d == 0) throw new ArithmeticException("Division by zero");
                    left /= d;
                }
                else if (match('%')) { left %= factor(); }
                else break;
            }
            return left;
        }

        // 幂运算（右结合：2^3^2 = 2^9 = 512）
        private double factor() {
            double base = power();
            skipSpaces();
            if (match('^')) {
                double exp = factor();  // 递归 → 右结合
                return Math.pow(base, exp);
            }
            return base;
        }

        private double power() {
            skipSpaces();
            // 一元负号
            if (match('-')) return -power();
            if (match('+')) return power();

            // 数字
            double num = tryNumber();
            if (!Double.isNaN(num)) return num;

            // 标识符（常量或函数）
            String ident = tryIdent();
            if (ident != null) {
                skipSpaces();
                if (match('(')) {
                    double arg = expr();
                    expect(')');
                    return applyFunction(ident, arg);
                }
                // 常量
                return switch (ident) {
                    case "pi" -> Math.PI;
                    case "e" -> Math.E;
                    default -> throw new RuntimeException("Unknown constant: " + ident);
                };
            }

            // 括号
            if (match('(')) {
                double val = expr();
                expect(')');
                return val;
            }

            throw new RuntimeException("Unexpected end of expression at position " + pos);
        }

        private double applyFunction(String name, double arg) {
            return switch (name) {
                case "sqrt" -> Math.sqrt(arg);
                case "sin" -> Math.sin(arg);
                case "cos" -> Math.cos(arg);
                case "tan" -> Math.tan(arg);
                case "log" -> Math.log10(arg);     // base 10
                case "ln" -> Math.log(arg);         // natural log
                case "abs" -> Math.abs(arg);
                case "round" -> Math.round(arg);
                default -> throw new RuntimeException("Unknown function: " + name);
            };
        }

        // ── 低级解析辅助 ──

        private void skipSpaces() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        private boolean match(char c) {
            if (pos < input.length() && input.charAt(pos) == c) { pos++; return true; }
            return false;
        }

        private void expect(char c) {
            skipSpaces();
            if (!match(c)) throw new RuntimeException("Expected '" + c + "' at position " + pos);
        }

        private double tryNumber() {
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (pos == start) return Double.NaN;
            return Double.parseDouble(input.substring(start, pos));
        }

        private String tryIdent() {
            int start = pos;
            while (pos < input.length() && Character.isLetter(input.charAt(pos))) pos++;
            if (pos == start) return null;
            return input.substring(start, pos);
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
