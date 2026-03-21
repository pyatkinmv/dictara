from fastapi.testclient import TestClient
from app import app


def test_metrics_endpoint():
    client = TestClient(app)
    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert "build_info" in resp.text


def test_health_endpoint():
    client = TestClient(app)
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
