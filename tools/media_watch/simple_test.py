import sys
print("hello stdout")
print("hello stderr", file=sys.stderr)
sys.exit(1)
