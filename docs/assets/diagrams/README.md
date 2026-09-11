# Diagrams

The images used throughout the documentation. Each diagram exists as an SVG (the source of truth, editable and crisp at any size) and a 2× PNG (what the docs embed).

| Diagram | Used in |
|---|---|
| `system-architecture` | [README](../../../README.md), [system context](../../architecture/system-context.md) |
| `service-communication` | [service communication](../../architecture/service-communication.md) |
| `flow-authentication`, `flow-payment`, `flow-webhook-delivery`, `flow-settlement` | [request flows](../../architecture/README.md) |
| `er-merchant`, `er-payment`, `er-vault`, `er-operations` | [data model](../../schema.md) |
| `state-payment`, `state-settlement`, `state-delivery` | [enums and state machines](../../schema.md) |
| `deployment-topology` | [README](../../../README.md), [deployment](../../deployment.md) |

## Regenerating

The diagrams are generated from code in `src/`, so they stay consistent in style and can be updated alongside the system:

```bash
cd docs/assets/diagrams/src
python build.py      # writes out/*.svg
python render.py     # renders out/*.png at 2x with headless Chrome or Edge
cp out/* ..          # replace the committed images
```

Python 3 and Chrome (or Edge) are the only requirements. Edit the `d_*.py` file that owns a diagram — `d_architecture`, `d_platform` (service communication, Kubernetes topology), `d_flows` (request flows), `d_er` (entity-relationship), `d_states` (state machines); `kit.py` holds the shared theme, layout primitives, sequence and ER renderers.

When the system changes — a new service, endpoint, table, status or pipeline step — update the diagram in the same change as the docs that describe it.

## Icons

Technology logos come from [Simple Icons](https://simpleicons.org/) (CC0 1.0), in `src/icons/`. Each logo is a trademark of its owner and is used only to identify the technology. Generic glyphs (Kafka, cards, banks, webhooks, …) are drawn in `kit.py`.
