$ErrorActionPreference = "Stop"

$env:LAOBAI_SKIP_LOCAL_CONFIG = "1"
$env:LAOBAI_PLANNER_ENDPOINT = ""
$env:LAOBAI_PLANNER_MODEL = ""
$env:LAOBAI_PLANNER_API_KEY = ""
$env:LAOBAI_EDGE_ENDPOINT = ""
$env:LAOBAI_EDGE_MODEL = ""
$env:LAOBAI_EDGE_API_KEY = ""
$env:LAOBAI_LOCAL_GEMMA_ENABLED = "1"
$env:LAOBAI_LOCAL_GEMMA_MODEL_PATH = "models/gemma-4-E4B-it.litertlm"
$env:LAOBAI_LOCAL_GEMMA_PYTHON = ".venv/Scripts/python.exe"

node .\tools\demo-server.mjs --host 127.0.0.1 --demo always-on
