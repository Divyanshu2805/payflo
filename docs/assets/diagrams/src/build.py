"""
Builds every documentation diagram into ./out as SVG; render.py then turns each into a PNG.

Handles: the list of diagrams and their output file names.
"""
import os
import d_architecture, d_platform, d_flows, d_er, d_states

HERE = os.path.dirname(os.path.abspath(__file__))
DIAGRAMS = {
    "system-architecture": d_architecture.build,
    "service-communication": d_platform.service_communication,
    "deployment-topology": d_platform.deployment_topology,
    "flow-authentication": d_flows.auth,
    "flow-payment": d_flows.payment,
    "flow-webhook-delivery": d_flows.webhooks,
    "flow-settlement": d_flows.settlement,
    "er-merchant": d_er.merchant,
    "er-payment": d_er.payment,
    "er-vault": d_er.vault,
    "er-operations": d_er.operations,
    "state-payment": d_states.payment,
    "state-settlement": d_states.settlement,
    "state-delivery": d_states.delivery,
}

if __name__ == "__main__":
    os.makedirs(os.path.join(HERE, "out"), exist_ok=True)
    for name, fn in DIAGRAMS.items():
        fn().save(os.path.join(HERE, "out", name + ".svg"))
        print("built", name)
