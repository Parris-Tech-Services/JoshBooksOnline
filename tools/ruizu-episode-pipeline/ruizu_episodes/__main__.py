import os
import sys

from .cli import main

os.umask(0o077)
sys.exit(main())
