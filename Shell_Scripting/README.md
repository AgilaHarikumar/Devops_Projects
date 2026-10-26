| Bash syntax or command | Purpose |
|---|---|
| `#!/bin/bash` | Specifies Bash as the interpreter when the script is executed directly. |
| `SOURCE="myfiles"` | Stores the source directory in a variable. |
| `[ ! -d "$SOURCE" ]` | Checks whether the source path is not a directory. |
| `for file in "$SOURCE"/*` | Iterates over items directly inside the source directory. |
| `[ ! -f "$file" ]` | Identifies items that are not regular files. |
| `continue` | Skips the current loop iteration. |
| `${file##*/}` | Extracts the filename from a path. |
| `${filename##*.}` | Extracts the portion after the last dot. |
| `mkdir -p` | Creates the destination directory if it does not already exist. |
| `mv -n` | Moves the file without overwriting an existing destination file. |
| `exit 1` | Exits with a nonzero status, conventionally indicating an error. |
| `if`, `then`, `else`, `fi` | Execute commands conditionally. |
| `do`, `done` | Mark the body and end of a loop. |
