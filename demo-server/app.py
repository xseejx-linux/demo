import time
import json
import re
import sqlite3
from flask import Flask, request, jsonify, render_template, g

app = Flask(__name__)

DATABASE = 'metadata.db'

# Global variables
pending_instructions = None
last_result = None
current_computer_id = None

# ----------------------------------------------------------------------
# Database helpers
# ----------------------------------------------------------------------
def get_db():
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    """Drop all tables and recreate them."""
    conn = sqlite3.connect(DATABASE)
    cursor = conn.cursor()
    cursor.execute("DROP TABLE IF EXISTS all_collectors")
    cursor.execute("DROP TABLE IF EXISTS computers")
    cursor.execute("DROP TABLE IF EXISTS all_taskid")
    cursor.execute("""
        CREATE TABLE computers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            computer_name TEXT UNIQUE NOT NULL
        )
    """)
    cursor.execute("""
        CREATE TABLE all_collectors (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            description TEXT,
            tags TEXT,
            parameters TEXT,
            computer_id INTEGER,
            FOREIGN KEY (computer_id) REFERENCES computers (id)
        )
    """)
    cursor.execute("""
        CREATE TABLE all_taskid (
            task_id TEXT PRIMARY KEY,
            computer_id INTEGER NOT NULL,
            FOREIGN KEY (computer_id) REFERENCES computers (id)
        )
    """)
    conn.commit()
    conn.close()

def save_computer(computer_name):
    conn = get_db()
    try:
        conn.execute("INSERT OR IGNORE INTO computers (computer_name) VALUES (?)", (computer_name,))
        conn.commit()
        row = conn.execute("SELECT id FROM computers WHERE computer_name = ?", (computer_name,)).fetchone()
        return row['id'] if row else None
    finally:
        conn.close()

def save_collector(computer_id, name, description, tags, parameters):
    conn = get_db()
    try:
        conn.execute(
            "INSERT INTO all_collectors (name, description, tags, parameters, computer_id) VALUES (?, ?, ?, ?, ?)",
            (name, description, json.dumps(tags), json.dumps(parameters), computer_id)
        )
        conn.commit()
    finally:
        conn.close()

def save_task_id(task_id, computer_id):
    conn = get_db()
    try:
        conn.execute(
            "INSERT OR REPLACE INTO all_taskid (task_id, computer_id) VALUES (?, ?)",
            (task_id, computer_id)
        )
        conn.commit()
    except sqlite3.IntegrityError as e:
        print("Task ID save error:", e)
    finally:
        conn.close()

def get_all_metadata():
    conn = get_db()
    try:
        rows = conn.execute("""
            SELECT c.id, c.name, c.description, c.tags, c.parameters,
                   comp.id AS computer_id, comp.computer_name
            FROM all_collectors c
            JOIN computers comp ON c.computer_id = comp.id
        """).fetchall()
        result = []
        for row in rows:
            result.append({
                "id": row["id"],
                "name": row["name"],
                "description": row["description"],
                "tags": json.loads(row["tags"]) if row["tags"] else [],
                "parameters": json.loads(row["parameters"]) if row["parameters"] else [],
                "computer": {
                    "id": row["computer_id"],
                    "name": row["computer_name"]
                }
            })
        return result
    finally:
        conn.close()

# ----------------------------------------------------------------------
# Metadata parsing
# ----------------------------------------------------------------------
def parse_parameters_from_string(params_str):
    if not params_str or params_str.strip() == "[]":
        return []
    matches = re.findall(r'\(([^)]*)\)', params_str)
    parameters = []
    for match in matches:
        param = {}
        items = [item.strip() for item in match.split(', ')]
        for item in items:
            if '=' in item:
                key, value = item.split('=', 1)
                param[key] = value
        if param:
            parameters.append(param)
    return parameters

def parse_collector_metadata(metadata_list, unlisted_params=None):
    name = ""
    description = ""
    tags = []
    params_raw = ""
    parameters = []

    for line in metadata_list:
        if line.startswith("name:"):
            name = line.split(":", 1)[1].strip()
        elif line.startswith("description:"):
            description = line.split(":", 1)[1].strip()
        elif line.startswith("tags:"):
            tag_str = line.split(":", 1)[1].strip()
            tag_str = tag_str.strip("[]")
            tags = [t.strip() for t in tag_str.split(",") if t.strip()]
        elif line.startswith("parameters:"):
            params_raw = line.split(":", 1)[1].strip()

    if unlisted_params and isinstance(unlisted_params, list) and len(unlisted_params) > 0:
        parameters = unlisted_params
    else:
        parameters = parse_parameters_from_string(params_raw)

    return {
        "name": name,
        "description": description,
        "tags": tags,
        "parameters": parameters
    }

# ----------------------------------------------------------------------
# Routes
# ----------------------------------------------------------------------
@app.route("/")
def index():
    return render_template("index.html")

# ------------ Public / web connector ------------
@app.route("/api/send_instruction", methods=["POST"])
def send_instruction():
    """Generic instruction (kept for backwards compatibility)."""
    global pending_instructions
    data = request.get_json()
    message = data.get("message")
    if message is None:
        return jsonify({"type": "error", "message": "message cannot be null"}), 400
    pending_instructions = {"type": "instruction", "message": message}
    print("[INSTRUCTION STORED]", pending_instructions)
    return jsonify({"type": "status", "message": "sent"})

@app.route("/api/send_instruction_add_task", methods=["POST"])
def send_add_task():
    """Queue a new task creation instruction."""
    global pending_instructions
    data = request.get_json()
    # Expects: { "name": ..., "parameters": [...], "cron": "...", "group": "...", "dispatcher": "..." }
    name = data.get("name")
    parameters = data.get("parameters", [])
    cron = data.get("cron", "*/5 * * * * ?")        # default cron if missing
    group = data.get("group", "default")
    dispatcher = data.get("dispatcher", "rabbitmq")

    if not name:
        return jsonify({"type": "error", "message": "task name required"}), 400

    instruction = {
        "type": "instruction_add_task",
        "message": [{
            "name": name,
            "parameters": parameters,
            "cron-value": cron,
            "group": group,
            "dispatcher": dispatcher
        }]
    }
    pending_instructions = instruction
    print("[ADD TASK INSTRUCTION STORED]", instruction)
    return jsonify({"type": "status", "message": "add_task_queued"})

@app.route("/api/send_instruction_del_task", methods=["POST"])
def send_del_task():
    """Queue a task deletion instruction."""
    global pending_instructions
    data = request.get_json()
    task_id = data.get("task_id")
    if not task_id:
        return jsonify({"type": "error", "message": "task_id required"}), 400

    instruction = {
        "type": "instruction_del_task",
        "message": task_id
    }
    pending_instructions = instruction
    print("[DEL TASK INSTRUCTION STORED]", instruction)
    return jsonify({"type": "status", "message": "del_task_queued"})

@app.route("/api/get_result", methods=["GET"])
def get_result():
    global last_result
    if last_result is None:
        return jsonify({"type": "result", "message": None})
    result = last_result
    last_result = None
    return jsonify(result)

# ------------ Java connector polling ------------
@app.route("/api/get_instruction", methods=["GET"])
def get_instruction():
    global pending_instructions
    for _ in range(60):
        if pending_instructions is not None:
            temp = pending_instructions
            pending_instructions = None
            return jsonify(temp)
        time.sleep(0.5)
    return jsonify({"type": "status", "message": None})

@app.route("/api/submit_result", methods=["POST"])
def submit_result():
    global last_result
    data = request.get_json()
    last_result = data
    print("[RESULT]", data)
    return jsonify({"type": "status", "message": "ok"})

# ------------ Metadata ------------
@app.route("/api/metadata", methods=["POST"])
def metadata():
    global current_computer_id
    data = request.get_json()
    if current_computer_id is None:
        return jsonify({"type": "error", "message": "No computer registered. Send /api/hello first."}), 400
    collectors = data.get("messagge", data.get("message", []))
    if not isinstance(collectors, list) or not collectors:
        return jsonify({"type": "error", "message": "Invalid or empty metadata"}), 400
    for collector in collectors:
        meta_raw = collector.get("metadata", [])
        unlisted = collector.get("unlistedParameters", None)
        parsed = parse_collector_metadata(meta_raw, unlisted)
        save_collector(
            current_computer_id,
            parsed["name"],
            parsed["description"],
            parsed["tags"],
            parsed["parameters"]
        )
    print(f"[METADATA STORED] {len(collectors)} collectors saved")
    return jsonify({"type": "status", "message": "ok"})

@app.route("/api/metadata", methods=["GET"])
def get_metadata():
    all_data = get_all_metadata()
    return jsonify({"type": "metadata", "data": all_data})

# ------------ Task reporting from Java client ------------
@app.route("/api/task_created", methods=["POST"])
def task_created():
    """Java client reports a newly created task ID."""
    data = request.get_json()
    task_id = data.get("task_id")
    computer_id = data.get("computer_id")
    if not task_id or not computer_id:
        return jsonify({"type": "error", "message": "task_id and computer_id required"}), 400

    save_task_id(task_id, computer_id)
    print(f"[TASK STORED] task_id={task_id}, computer_id={computer_id}")
    return jsonify({"type": "status", "message": "task_stored"})

# ------------ Hello / registration ------------
@app.route("/api/hello", methods=["POST"])
def hello():
    global current_computer_id
    data = request.get_json()
    computer_name = data.get("message")
    if not computer_name:
        return jsonify({"type": "error", "message": "No computer name provided"}), 400

    computer_id = save_computer(computer_name)
    current_computer_id = computer_id
    print(f"[HELLO] Computer {computer_name} registered with ID {computer_id}")
    return jsonify({"type": "hello", "message": "Hello-From-Server"})

if __name__ == "__main__":
    init_db()
    app.run(host="0.0.0.0", port=8979, debug=True)