package com.jacekgajek.kapelusz.app.util

import com.conamobile.pdfkmp.PdfDocument
import com.conamobile.pdfkmp.pdf
import com.conamobile.pdfkmp.unit.sp
import com.jacekgajek.kapelusz.model.Band
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeFormat

object PdfExporter {
    fun generatePdf(band: Band, option: PdfGenerateOptions): PdfDocument {
        return pdf {
            page {
                header {
                    text("Kapelusz") {
                        fontSize = 20.sp
                    }
                }

                if (option.events) {
                    text("Events") {
                        fontSize = 15.sp
                    }

                    for (e in band.events) {
                        columns {
                            column {
                                text(e.name)
                            }
                            column {
                                text(e.date.format(LocalDate.Formats.ISO))
                            }
                        }
                    }
                }
            }
        }
    }

    class PdfGenerateOptions(
        val events: Boolean
    )
}
