package v1

import "testing"

func FuzzWireCommandDecoder(f *testing.F) {
	for _, seed := range []string{
		`{"protocol":{"major":1,"minor":0},"command_id":"cmd_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","operation_id":"op_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","kind":"runtime.ping","payload":{}}`,
		`{}`,
		`{"protocol":{"major":999,"minor":0}}`,
		`not-json`,
	} {
		f.Add([]byte(seed))
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) > 64*1024 {
			data = data[:64*1024]
		}
		env, err := DecodeCommand(data)
		if err != nil {
			return
		}
		if err := env.Validate(); err != nil {
			t.Fatalf("decoder returned invalid command: %v", err)
		}
	})
}
