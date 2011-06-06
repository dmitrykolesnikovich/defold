import sys
import vscript_ddf_pb2
from vscript_ddf_pb2 import VisualScript
from google.protobuf import text_format

class Interpreter(object):

    def __init__(self, source):
        self.script = VisualScript()
        text_format.Merge(source, self.script)
        self.script.SerializeToString()
        self.stack = []
        self.globals = {}

    def get_global(self, name):
        return self.globals[name]

    def set_global(self, name, value):
        self.globals[name] = value

    def push(self, value):
        self.stack.insert(0, value)

    def pop(self):
        return self.stack.pop(0)

    def visit_node(self, node):
        for stmt in node.statements:
            self.visit_statement(stmt)

    # Generic statement dispatch
    def visit_statement(self, stmt):
        name = vscript_ddf_pb2._STATEMENTTYPE.values_by_number[stmt.type].name
        name = name.lower()
        method = getattr(self, 'visit_%s' % name)
        method(stmt)

    # Statements
    def visit_if(self, stmt):
        self.visit_expression(stmt.expression)
        cond = self.pop()
        if cond:
            for stmt in stmt.statements:
                self.visit_statement(stmt)

    # Statements
    def visit_if_else(self, stmt):
        self.visit_expression(stmt.expression)
        cond = self.pop()
        if cond:
            for stmt in stmt.statements:
                self.visit_statement(stmt)
        else:
            for stmt in stmt.else_statements:
                self.visit_statement(stmt)

    def visit_set_variable(self, stmt):
        self.visit_expression(stmt.expression)
        self.globals[stmt.variable] = self.pop()

    def visit_print(self, stmt):
        self.visit_expression(stmt.expression)
        print self.pop()

    # Generic expression dispatch
    def visit_expression(self, expr):
        name = vscript_ddf_pb2._EXPRESSIONTYPE.values_by_number[expr.type].name
        name = name.lower()
        method = getattr(self, 'visit_%s' % name)
        method(expr)

    # Expressions
    def do_binary(self, expr, func):
        self.visit_expression(expr.expressions[0])
        self.visit_expression(expr.expressions[1])
        v2, v1 = self.pop(), self.pop()
        self.push(func(v1, v2))

    def visit_add(self, expr):
        self.do_binary(expr, lambda x, y: x + y)

    def visit_sub(self, expr):
        self.do_binary(expr, lambda x, y: x - y)

    def visit_mul(self, expr):
        self.do_binary(expr, lambda x, y: x * y)

    def visit_div(self, expr):
        self.do_binary(expr, lambda x, y: x / y)

    def visit_lt(self, expr):
        self.do_binary(expr, lambda x, y: x < y)

    def visit_le(self, expr):
        self.do_binary(expr, lambda x, y: x <= y)

    def visit_eq(self, expr):
        self.do_binary(expr, lambda x, y: x == y)

    def visit_ge(self, expr):
        self.do_binary(expr, lambda x, y: x >= y)

    def visit_gt(self, expr):
        self.do_binary(expr, lambda x, y: x > y)

    def visit_constant(self, expr):
        if expr.constant.type.basic_type == vscript_ddf_pb2.NUMBER:
            self.push(expr.constant.float_value)
        elif expr.constant.type.basic_type == vscript_ddf_pb2.STRING:
            self.push(expr.constant.string_value)
        else:
            assert False

    def visit_load_variable(self, expr):
        value = self.globals[expr.variable]
        self.push(value)

    def run(self):
        for node in self.script.nodes:
            self.visit_node(node)

if __name__ == '__main__':
    source = open(sys.argv[1], 'r').read()
    interpreter = Interpreter(source)
    interpreter.run()
