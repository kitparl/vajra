from google.protobuf.any_pb2 import Any
from google.protobuf.timestamp_pb2 import Timestamp
from vajra.common.v1.event_envelope_pb2 import EventEnvelope
from vajra.events.v1.signal_generated_pb2 import SignalGenerated


def test_signal_generated_round_trips_in_envelope() -> None:
    signal = SignalGenerated(
        signal_id="7c1f6a52-3c1e-4f0e-9a54-0d2f3b9f1a10",
        strategy="heartbeat",
        symbol="BTCUSD",
        side="BUY",
        order_type="MARKET",
        size=1,
        generated_at=Timestamp(seconds=1_700_000_000),
    )
    payload = Any()
    payload.Pack(signal)
    envelope = EventEnvelope(event_type="signal.generated", payload=payload)

    parsed = EventEnvelope.FromString(envelope.SerializeToString())

    assert parsed.payload.Is(SignalGenerated.DESCRIPTOR)
    unpacked = SignalGenerated()
    assert parsed.payload.Unpack(unpacked)
    assert unpacked == signal
    assert unpacked.signal_id == "7c1f6a52-3c1e-4f0e-9a54-0d2f3b9f1a10"
    assert unpacked.strategy == "heartbeat"
    assert unpacked.symbol == "BTCUSD"
    assert unpacked.side == "BUY"
    assert unpacked.order_type == "MARKET"
    assert unpacked.size == 1
    assert unpacked.generated_at.seconds == 1_700_000_000
