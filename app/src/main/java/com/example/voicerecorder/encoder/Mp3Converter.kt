package com.example.voicerecorder.encoder

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.voicerecorder.settings.RecordingQuality
import java.io.File

object Mp3Converter {

    private const val TAG =
        "Mp3Converter"

    /**
     * Encodes [inputFile] to MP3 at the sample rate and bit rate of
     * [quality], which is what the user chose under Settings > Recording.
     *
     * The rate has to be given to the encoder here: a recording is
     * captured as AAC at the chosen quality and then re-encoded, so
     * without it every recording came out at one fixed rate however the
     * setting was left, and "Low" produced a bigger file than the audio
     * it came from.
     */
    fun convert(
        inputFile: File,
        outputFile: File,
        quality: RecordingQuality,
        onComplete: (Boolean) -> Unit
    ) {

        Log.d(
            TAG,
            "INPUT: ${inputFile.absolutePath}"
        )

        Log.d(
            TAG,
            "INPUT EXISTS: ${inputFile.exists()}"
        )

        Log.d(
            TAG,
            "INPUT SIZE: ${inputFile.length()} bytes"
        )

        Log.d(
            TAG,
            "OUTPUT: ${outputFile.absolutePath}"
        )

        Log.d(
            TAG,
            "QUALITY: ${quality.name} (${quality.sampleRate} Hz, ${quality.bitRate} bps)"
        )

        // Bit rates are given in bits per second, so 32000 stays exactly 32000.
        val command =
            "-y " +
                    "-i \"${inputFile.absolutePath}\" " +
                    "-vn " +
                    "-codec:a libmp3lame " +
                    "-ar ${quality.sampleRate} " +
                    "-b:a ${quality.bitRate} " +
                    "\"${outputFile.absolutePath}\""

        Log.d(
            TAG,
            "FFmpeg command: $command"
        )

        try {

            FFmpegKit.executeAsync(
                command
            ) { session ->

                val returnCode =
                    session.returnCode

                val success =
                    ReturnCode.isSuccess(
                        returnCode
                    )

                Log.d(
                    TAG,
                    "FFMPEG RETURN CODE: $returnCode"
                )

                Log.d(
                    TAG,
                    "FFMPEG SUCCESS: $success"
                )

                Log.d(
                    TAG,
                    "OUTPUT EXISTS: ${outputFile.exists()}"
                )

                Log.d(
                    TAG,
                    "OUTPUT SIZE: ${outputFile.length()} bytes"
                )

                onComplete(
                    success &&
                            outputFile.exists() &&
                            outputFile.length() > 0
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "FFmpeg execution failed",
                e
            )

            onComplete(false)
        }
    }
}