import time

from flask import Flask, request, jsonify, render_template

app = Flask(__name__)


pending_instructions = None
last_result = None

@app.route("/")
def index():
    return render_template("index.html")


'''
APIS FOR PUBLIC CONNECTORS
'''
@app.route("/api/send_instruction", methods=["POST"])
def getDevices():

    global pending_instructions
    data = request.get_json()

    message = data.get("message")

    if message is None:
        return jsonify({
            "type": "error",
            "message": "message cannot be null"
        }), 400

    pending_instructions = {
        "type": "instruction",
        "message": message
    }

    print("[INSTRUCTION STORED]", pending_instructions)

    return jsonify({
        "type": "status",
        "message": "sent"
    })

@app.route("/api/get_result", methods=["GET"])
def get_result():
    global last_result

    if last_result is None:
        return jsonify({
            "type": "result",
            "message":None
        })

    result = last_result
    last_result = None

    return jsonify(result)


'''
APIS FOR JAVA CONNECTORS
'''
@app.route("/api/get_instruction", methods=["GET"])
def get_instruction():
    global pending_instructions

    for _ in range(60):
        if pending_instructions is not None:
            temp = pending_instructions
            pending_instructions = None
            return jsonify(temp)
        time.sleep(0.5)

    return jsonify({
        "type": "status",
        "message": None
    })


@app.route("/api/submit_result", methods=["POST"])
def submit_result():
    global last_result
    data = request.get_json()

    last_result = data

    print("[RESULT]", data)

    return jsonify({
        "type":"status",
        "message": "ok"
    })

@app.route("/api/hello", methods=["POST"])
def hello():
    data = request.get_json()

    print("[DATA_FLOW]", data)

    return jsonify({
        "type": "hello",
        "message": "Hello-From-Server"
    })



if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8979, debug=True)