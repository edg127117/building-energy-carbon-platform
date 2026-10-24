"""Apply the sole D01 change to the hash-pinned upstream backup client."""
from pathlib import Path

path = Path('src/taosdump.c')
source = path.read_text()
old = '''                if (scanf("%20s", arguments->password) > 1) {
                    errorPrint("%s() LN%d, password read error!\\n",
                            __func__, __LINE__);
                }'''
new = '''                /* A full line avoids the legacy 20-byte scanf truncation.
                 * Reject overflow/EOF rather than authenticate a prefix. */
                char password_line[sizeof(arguments->password) + 1];
                if (!fgets(password_line, sizeof(password_line), stdin)) {
                    fprintf(stderr, "D01_TD_PASSWORD_INPUT_INVALID\\n");
                    exit(EXIT_FAILURE);
                }
                size_t password_len = strlen(password_line);
                if (password_len < 2 || password_len > sizeof(arguments->password) || password_line[password_len - 1] != '\\n') {
                    fprintf(stderr, "D01_TD_PASSWORD_INPUT_INVALID\\n");
                    exit(EXIT_FAILURE);
                }
                memcpy(arguments->password, password_line, password_len - 1);
                arguments->password[password_len - 1] = '\\0';
                memset(password_line, 0, sizeof(password_line));'''
assert source.count(old) == 1, 'D01_UPSTREAM_PASSWORD_BLOCK_CHANGED'
path.write_text(source.replace(old, new))
