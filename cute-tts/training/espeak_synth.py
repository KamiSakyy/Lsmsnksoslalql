"""Synthesize wav via libespeak-ng (from espeakng_loader) using ctypes. Offline, no binaries."""
import array
import ctypes
import wave


def synth_to_wav(text: str, wav_path: str, voice: str = "ru", rate: int = 160,
                 pitch: int = 60, gap: int = 2, variant: str = "") -> int:
    import espeakng_loader

    lib = espeakng_loader.load_library()
    espeak_Initialize = lib.espeak_Initialize
    espeak_Initialize.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
    espeak_Initialize.restype = ctypes.c_int
    espeak_SetVoiceByName = lib.espeak_SetVoiceByName
    espeak_SetVoiceByName.argtypes = [ctypes.c_char_p]
    espeak_SetVoiceByName.restype = ctypes.c_int
    espeak_SetParameter = lib.espeak_SetParameter
    espeak_SetParameter.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int]
    espeak_Synth = lib.espeak_Synth
    espeak_Synth.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_uint,
                             ctypes.c_int, ctypes.c_uint, ctypes.c_uint,
                             ctypes.POINTER(ctypes.c_uint), ctypes.c_void_p]
    espeak_SetSynthCallback = lib.espeak_SetSynthCallback

    samples = []
    CMPFUNC = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.POINTER(ctypes.c_short),
                               ctypes.c_int, ctypes.c_void_p)

    def callback(wav, num, events):
        if wav and num > 0:
            samples.extend(wav[:num])
        return 0

    c_cb = CMPFUNC(callback)
    espeak_SetSynthCallback(c_cb)
    # AUDIO_OUTPUT_SYNCHRONOUS = 2
    espeak_Initialize(2, 500, espeakng_loader.get_data_path().encode(), 0)
    name = voice + ("+" + variant if variant else "")
    espeak_SetVoiceByName(name.encode())
    espeak_SetParameter(1, rate, 0)    # espeakRATE
    espeak_SetParameter(2, pitch, 0)   # espeakPITCH
    espeak_SetParameter(3, gap, 0)     # espeakWORDGAP
    raw = text.encode("utf-8")
    # espeakCHARS_UTF8 = 1, POS_CHARACTER = 1
    espeak_Synth(raw, len(raw) + 1, 0, 1, 0, 1, None, None)
    # flush remaining audio
    espeak_Synth(None, 0, 0, 2, 0, 0, None, None)

    with wave.open(wav_path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(22050)
        arr = array.array("h", samples)
        f.writeframes(arr.tobytes())
    return len(samples)


if __name__ == "__main__":
    import sys
    text = sys.argv[1] if len(sys.argv) > 1 else "Привет, мир! Меня зовут Мила."
    out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/test_espeak.wav"
    n = synth_to_wav(text, out, variant="f2")
    print("samples:", n, "->", out)
