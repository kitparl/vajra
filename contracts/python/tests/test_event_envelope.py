from google.protobuf.any_pb2 import Any
from google.protobuf.timestamp_pb2 import Timestamp
from google.protobuf.wrappers_pb2 import StringValue
from vajra.common.v1.event_envelope_pb2 import EventEnvelope


def test_round_trips_through_bytes() -> None:
    payload = Any()
    payload.Pack(StringValue(value="hi"))
    original = EventEnvelope(
        event_id="e-1",
        event_type="test.ping",
        producer="brahma",
        occurred_at=Timestamp(seconds=1_700_000_000),
        payload=payload,
    )

    parsed = EventEnvelope.FromString(original.SerializeToString())

    assert parsed == original
    unpacked = StringValue()
    assert parsed.payload.Unpack(unpacked)
    assert unpacked.value == "hi"
