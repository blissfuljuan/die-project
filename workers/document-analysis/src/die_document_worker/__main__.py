from .app import run
from .config import Settings

if __name__ == "__main__":
    run(Settings.from_env())
