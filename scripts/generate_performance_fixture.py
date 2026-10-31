import argparse
import hashlib
import json
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

NAMESPACE = uuid.UUID("de78a859-961e-5c64-a910-975dccda4585")
GENERATOR_VERSION = "1.0"


def uid(kind, index):
    return str(uuid.uuid5(NAMESPACE, f"ivy-performance-v1:{kind}:{index}"))


def build(count):
    accounts = [{"name": f"Synthetic USD Account {i + 1}", "currency": "USD", "color": [-14575886, -10526881][i], "icon": None, "orderNum": i, "includeInBalance": True, "archived": False, "isSynced": False, "isDeleted": False, "id": uid("account", i)} for i in range(2)]
    categories = [{"name": f"Synthetic Category {i + 1}", "color": [-14575886, -10526881, -7697782, -4382718, -16738680][i], "icon": None, "orderNum": i, "isSynced": False, "isDeleted": False, "id": uid("category", i)} for i in range(5)]
    transactions = []
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    for i in range(count):
        transfer = i % 10 == 0
        income = i % 10 == 1
        account = i % 2
        amount = ((i * 7919) % 99999 + 1) / 100
        item = {"accountId": accounts[account]["id"], "type": "TRANSFER" if transfer else "INCOME" if income else "EXPENSE", "amount": amount, "toAccountId": accounts[1-account]["id"] if transfer else None, "toAmount": amount if transfer else None, "title": f"Synthetic benchmark transaction {i + 1}", "description": None, "dateTime": (start + timedelta(days=i % 365, seconds=i % 86400)).isoformat(timespec="milliseconds").replace("+00:00", "Z"), "categoryId": categories[i % 5]["id"], "dueDate": None, "recurringRuleId": None, "paidForDateTime": None, "attachmentUrl": None, "loanId": None, "loanRecordId": None, "isSynced": False, "isDeleted": False, "id": uid("transaction", i)}
        transactions.append(item)
    settings = [{"theme": "AUTO", "currency": "USD", "bufferAmount": 0.0, "name": "Synthetic benchmark", "isSynced": False, "isDeleted": False, "id": uid("settings", 0)}]
    data = {"formatVersion": 2, "accounts": accounts, "budgets": [], "categories": categories, "loanRecords": [], "loans": [], "plannedPaymentRules": [], "settings": settings, "transactions": transactions, "sharedPrefs": {}, "tags": [], "tagAssociations": [], "manualExchangeRates": []}
    ids = [x["id"] for x in accounts + categories + settings + transactions]
    assert len(ids) == len(set(ids)) and len(accounts) == 2 and len(categories) == 5
    assert all(t["accountId"] in {a["id"] for a in accounts} and (t["categoryId"] in {c["id"] for c in categories}) and t["amount"] > 0 for t in transactions)
    assert all(t["toAccountId"] in {a["id"] for a in accounts} and t["toAmount"] == t["amount"] for t in transactions if t["type"] == "TRANSFER")
    return data


def main():
    parser = argparse.ArgumentParser(description="Generate deterministic synthetic IvyWallet backup fixture")
    parser.add_argument("--count", type=int, choices=(2000, 20000), required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    data = build(args.count)
    payload = b"\xff\xfe" + (json.dumps(data, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-16-le")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(payload)
    metadata = {"schema": "IvyWalletCompleteData", "formatVersion": 2, "generatorVersion": GENERATOR_VERSION, "count": args.count, "sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload), "datasetDates": {"start": min(t["dateTime"] for t in data["transactions"]), "end": max(t["dateTime"] for t in data["transactions"])}, "accountCount": 2, "categoryCount": 5}
    args.out.with_suffix(".metadata.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(metadata, sort_keys=True))


if __name__ == "__main__":
    main()
