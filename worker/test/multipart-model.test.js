import assert from "node:assert/strict";
import test from "node:test";
import { multipartModel } from "../src/index.js";

const BOUNDARY = "----jarvis.test";

function build(parts) {
  let text = "";
  for (const part of parts) {
    text += "--" + BOUNDARY + "\r\n";
    for (const header of part.headers) text += header + "\r\n";
    text += "\r\n" + part.value + "\r\n";
  }
  return text + "--" + BOUNDARY + "--\r\n";
}

function bytes(text) {
  return new TextEncoder().encode(text);
}

test("model part without extra headers parses", () => {
  const text = build([
    {
      headers: ['Content-Disposition: form-data; name="model"'],
      value: "whisper-large-v3-turbo",
    },
    {
      headers: ['Content-Disposition: form-data; name="file"; filename="utterance.wav"'],
      value: "RIFF....",
    },
  ]);
  assert.equal(multipartModel(bytes(text)), "whisper-large-v3-turbo");
});

test("model part with content length header parses", () => {
  const text = build([
    {
      headers: ['Content-Disposition: form-data; name="model"', "Content-Length: 21"],
      value: "whisper-large-v3-turbo",
    },
    {
      headers: ['Content-Disposition: form-data; name="file"; filename="utterance.wav"'],
      value: "RIFF....",
    },
  ]);
  assert.equal(multipartModel(bytes(text)), "whisper-large-v3-turbo");
});

test("empty model part does not parse", () => {
  const text = build([
    {
      headers: ['Content-Disposition: form-data; name="file"; filename="utterance.wav"'],
      value: "RIFF....",
    },
  ]);
  assert.equal(multipartModel(bytes(text)), "");
});

test("no bytes does not parse", () => {
  assert.equal(multipartModel(bytes("")), "");
});
