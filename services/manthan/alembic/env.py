from research_service.main import SCHEMA
from vajra_common.migrations import run_online

run_online(SCHEMA)
