#!/usr/bin/env python3
"""TASK-0015 — generate INVALID / malformed Indri queries to fuzz Lucindri's parser robustness.

Emits `category<TAB>query` per line. Feed the second column to FuzzInvalidHarness, which asserts the
robustness invariant: every query either parses (OK) or is rejected with a QueryParseException — never a
low-level crash (ArrayIndexOOB / StringIndexOOB / NumberFormat / NPE / StackOverflow) or a hang.

Three strategies: (1) grammar-aware invalids (one broken rule each), (2) mutation of legal queries
(corrupt a character / paren / weight), (3) adversarial/degenerate inputs.

  usage: fuzz_invalid.py <seed> <count> > invalid.tsv
"""
import random
import sys

TERMS = ["cat", "dog", "sun", "moon", "tree", "42", "the"]  # 'the' is a stopword; '42' a number token


def legal(rng, depth=2):
    """A small legal query, used as a base for mutation. All text is quoted (TASK-0016 quote-only grammar)."""
    if depth <= 0 or rng.random() < 0.4:
        return '"%s"' % rng.choice(TERMS)
    op = rng.choice(["#combine", "#or", "#max", "#syn", "#1", "#uw3", "#od2", "#weight", "#band"])
    k = rng.randint(2, 3)
    kids = [legal(rng, depth - 1) for _ in range(k)]
    if op in ("#1", "#uw3", "#od2", "#syn", "#band"):
        kids = ['"%s"' % rng.choice(TERMS) for _ in range(k)]  # proximity/syn operands are quoted literals
    if op == "#weight":
        body = " ".join("%.1f %s" % (rng.uniform(0.1, 1.0), c) for c in kids)
        return "#weight( %s )" % body
    return "%s( %s )" % (op, " ".join(kids))


def grammar_invalid(rng):
    t = lambda: rng.choice(TERMS)
    choices = [
        ("unbalanced-missing-close", "#combine( %s %s" % (t(), t())),
        ("unbalanced-missing-open", "#combine %s %s )" % (t(), t())),
        ("unbalanced-extra-close", "#combine( %s ) %s )" % (t(), t())),
        ("unbalanced-extra-open", "#combine( ( %s %s )" % (t(), t())),
        ("empty-combine", "#combine( )"),
        ("empty-window", "#uw2( )"),
        ("empty-syn", "#syn( )"),
        ("window-no-size", "#uw( %s %s )" % (t(), t())),
        ("ordered-no-size", "#od( %s %s )" % (t(), t())),
        ("distance-zero", "#0( %s %s )" % (t(), t())),
        ("distance-negative", "#-3( %s %s )" % (t(), t())),
        ("distance-overflow", "#99999999999( %s %s )" % (t(), t())),
        ("weight-nonnumeric", "#weight( abc %s )" % t()),
        ("weight-dangling", "#weight( 0.5 %s 0.7 )" % t()),
        ("weight-missing", "#weight( %s %s )" % (t(), t())),
        ("belief-in-proximity", "#1( #combine( %s %s ) %s )" % (t(), t(), t())),
        ("belief-in-proximity-or", "#uw5( #or( %s %s ) %s )" % (t(), t(), t())),
        ("unknown-operator", "#%s( %s %s )" % (rng.choice(["foo", "combyne", "wsyn", "prior", "less"]), t(), t())),
        ("near-spelling", "#near2( %s %s )" % (t(), t())),
        ("window-spelling", "#window3( %s %s )" % (t(), t())),
        ("trailing-field-dot", "%s." % t()),
        ("leading-field-dot", ".title %s" % t()),
        ("only-open-paren", "#combine("),
        ("bare-operator", "#combine"),
        # TASK-0016 quote-only grammar: unquoted text and stray '~' are now rejected.
        ("unquoted-term", "#combine( %s %s )" % (t(), t())),
        ("unquoted-token", "#token( %s )" % t()),
        ("unterminated-quote", '#combine( "%s )' % t()),
        ("unknown-escape", '#combine( "a\\x" )'),
        ("midchunk-quote", '#combine( say"hi" )'),
        ("trailing-after-quote", '#combine( "a"b )'),
        ("empty-token-op", "#token( )"),
        ("empty-token-literal", '#token( "" )'),
        ("tilde-operator", '~combine( "%s" "%s" )' % (t(), t())),
    ]
    return rng.choice(choices)


def mutate(rng):
    q = legal(rng, rng.randint(1, 3))
    kind = rng.choice(["drop-paren", "add-paren", "drop-char", "swap-char", "blank-weight", "insert-hash"])
    if kind == "drop-paren":
        i = q.find("(") if rng.random() < 0.5 else q.rfind(")")
        q = q[:i] + q[i + 1:] if i >= 0 else q + "("
    elif kind == "add-paren":
        q += rng.choice(["(", ")"])
    elif kind == "drop-char" and len(q) > 1:
        i = rng.randrange(len(q))
        q = q[:i] + q[i + 1:]
    elif kind == "swap-char" and len(q) > 2:
        i = rng.randrange(len(q) - 1)
        q = q[:i] + q[i + 1] + q[i] + q[i + 2:]
    elif kind == "blank-weight":
        q = q.replace("0.", "x.", 1)
    elif kind == "insert-hash":
        i = rng.randrange(len(q) + 1)
        q = q[:i] + "#" + q[i:]
    return ("mutation-" + kind, q)


def adversarial(rng):
    choices = [
        ("deep-nesting", "#combine( " * 400 + '"cat"' + " )" * 400),
        ("wide-operands", "#combine( " + " ".join(['"cat"'] * 5000) + " )"),
        ("huge-token", '#combine( "' + "a" * 20000 + ' cat" )'),
        ("only-whitespace", "     "),
        ("empty", ""),
        ("only-punctuation", "#()#()%s" % rng.choice(["", "()", "###"])),
        ("control-chars", '#combine( "cat\x00\x01 dog" )'),
        ("unicode", '#combine( "naïve café 東京" )'),
        ("nested-unbalanced", "#combine( #syn( cat #1( dog )"),
    ]
    return rng.choice(choices)


def main():
    seed = int(sys.argv[1])
    count = int(sys.argv[2])
    rng = random.Random(seed)
    strategies = [grammar_invalid, grammar_invalid, mutate, mutate, adversarial]
    for _ in range(count):
        cat, q = rng.choice(strategies)(rng)
        # keep it one line
        q = q.replace("\n", " ").replace("\r", " ")
        sys.stdout.write("%s\t%s\n" % (cat, q))


if __name__ == "__main__":
    main()
