/*
The MIT License (MIT)

Copyright (c) 2014 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


History:
* 2014 creation

*/
package com.github.lindenb.jvarkit.tools.misc;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import htsjdk.samtools.util.CloserUtil;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeader;

import com.github.lindenb.jvarkit.io.IOUtils;
import com.github.lindenb.jvarkit.util.command.Command;
import com.github.lindenb.jvarkit.util.picard.SAMSequenceDictionaryProgress;
import com.github.lindenb.jvarkit.util.so.SequenceOntologyTree;
import com.github.lindenb.jvarkit.util.vcf.VcfIterator;
import com.github.lindenb.jvarkit.util.vcf.predictions.VepPredictionParser;



public class VcfBurden extends AbstractVcfBurden
	{
	private static final org.apache.commons.logging.Log LOG = org.apache.commons.logging.LogFactory.getLog(VcfBurden.class);

	@Override
	public  Command createCommand() {
			return new MyCommand();
		}
		 
	public  static class MyCommand extends AbstractVcfBurden.AbstractVcfBurdenCommand
		 	{		
	
		private Map<String,Boolean> gene2seen=null;
		private String dirName="burden";
		
		
		
		private static class GeneTranscript
			{
			String geneName;
			String transcriptName;
			GeneTranscript(String geneName,String transcriptName)
				{
				this.geneName = geneName;
				this.transcriptName = transcriptName;
				}
			@Override
			public int hashCode() {
				final int prime = 31;
				int result = 1;
				result = prime * result
						+ ((geneName == null) ? 0 : geneName.hashCode());
				result = prime * result
						+ ((transcriptName == null) ? 0 : transcriptName.hashCode());
				return result;
				}
			@Override
			public boolean equals(Object obj) {
				if (this == obj) return true;
				if (obj == null) return false;
				if (getClass() != obj.getClass()) return false;
				GeneTranscript other = (GeneTranscript) obj;
				if (geneName == null) {
					if (other.geneName != null)
						return false;
				} else if (!geneName.equals(other.geneName))
					return false;
				if (transcriptName == null) {
					if (other.transcriptName != null)
						return false;
				} else if (!transcriptName.equals(other.transcriptName))
					return false;
				return true;
				}
			
			}
	
		
		
		private void dump(
				ZipOutputStream zout,
				String filename,
				List<String> samples,
				List<VariantContext> variants
				) throws IOException
			{
	
			ZipEntry ze = new ZipEntry(this.dirName+"/"+filename+".txt");
			zout.putNextEntry(ze);
			PrintWriter pw = new PrintWriter(zout);
			pw.print("CHROM\tPOS\tREF\tALT");
			for(String sample:samples)
				{
				pw.print("\t");
				pw.print(sample);
				}
			pw.println();
			for(VariantContext ctx:variants)
				{
				pw.print(ctx.getContig());
				pw.print("\t");
				pw.print(ctx.getStart());
				pw.print("\t");
				pw.print(ctx.getReference().getDisplayString());
				pw.print("\t");
				pw.print(ctx.getAlternateAlleles().get(0).getDisplayString());
				for(String sample:samples)
					{
					Genotype g=ctx.getGenotype(sample);
					pw.print("\t");
					if(g.isHomRef())
						{
						pw.print("0");
						}
					else if(g.isHomVar())
						{
						pw.print("2");
						}
					else if(g.isHet())
						{
						pw.print("1");
						}
					else
						{
						pw.print("-9");
						}
					}
				pw.println();
				}
			pw.flush();
			zout.closeEntry();
			}
		
			@Override
			protected Collection<Throwable> call(String inputName) throws Exception
				{
				
				if(super.geneFile!=null)
					{
					BufferedReader in=null;
					try {
						if(this.gene2seen==null) this.gene2seen=new HashMap<>();
						in = IOUtils.openFileForBufferedReading(super.geneFile);
						String line;
						while((line=in.readLine())!=null)
							{
							line=line.trim();
							if(line.isEmpty()||line.startsWith("#")) continue;
							this.gene2seen.put(line, Boolean.FALSE);
							}
						}
					catch (Exception e) {
						return wrapException(e);
						}
					finally
						{
						CloserUtil.close(in);
						}
					}
			
				ZipOutputStream zout=null;
				FileOutputStream fout=null;
				VcfIterator in=null;
				try
					{
					in = super.openVcfIterator(inputName);
					if(getOutputFile()==null)
						{
						return wrapException("undefined output");
						}
					else if(!getOutputFile().getName().endsWith(".zip"))
						{
						return wrapException("output "+getOutputFile()+" should end with .zip");
						}
					else
						{
						fout = new FileOutputStream(getOutputFile());
						zout = new ZipOutputStream(fout);
						}
					List<String> samples= in.getHeader().getSampleNamesInOrder();
					VCFHeader header=in.getHeader();
					String prev_chrom = null;
					VepPredictionParser vepPredParser=new VepPredictionParser(header);
					Map<GeneTranscript,List<VariantContext>> gene2variants=new HashMap<>();
					SequenceOntologyTree soTree= SequenceOntologyTree.getInstance();
					Set<SequenceOntologyTree.Term> acn=new HashSet<>();
					for(String acns:new String[]{
							"SO:0001589", "SO:0001587", "SO:0001582", "SO:0001583",
							"SO:0001575", "SO:0001578", "SO:0001574", "SO:0001889",
							"SO:0001821", "SO:0001822", "SO:0001893"
							})
						{
						acn.addAll(soTree.getTermByAcn(acns).getAllDescendants());
						}
					
				
					SAMSequenceDictionaryProgress progress=new SAMSequenceDictionaryProgress(in.getHeader());
					for(;;)
						{
						VariantContext ctx1=null;
						if(in.hasNext())
							{
							ctx1= progress.watch(in.next());
							if(ctx1.getAlternateAlleles().size()!=1)
								{
								//info("count(ALT)!=1 in "+ctx1.getChr()+":"+ctx1.getStart());
								continue;
								}
							}
						
						if(ctx1==null || !ctx1.getContig().equals(prev_chrom))
							{
							LOG.info("DUMP to zip n="+gene2variants.size());
							Set<String> geneNames= new HashSet<>();
							for(GeneTranscript gene_transcript:gene2variants.keySet() )
								{
								geneNames.add(gene_transcript.geneName);
								dump(zout,
										gene_transcript.geneName+"_"+gene_transcript.transcriptName,
										samples,
										gene2variants.get(gene_transcript)
										);
								}
							
							for(String geneName : geneNames)
								{
								Comparator<VariantContext> cmp = new Comparator<VariantContext>()
											{
											@Override
											public int compare(VariantContext o1,
													VariantContext o2) {
												int i = o1.getContig().compareTo(o2.getContig());
												if(i!=0) return i;
												i = o1.getStart() - o2.getStart();
												if(i!=0) return i;
												i = o1.getReference().compareTo(o2.getReference());
												if(i!=0) return i;
												i = o1.getAlternateAllele(0).compareTo(o2.getAlternateAllele(0));
												if(i!=0) return i;
												return 0;
												}
											};
								SortedSet<VariantContext> lis_nm = new TreeSet<>(cmp);
								SortedSet<VariantContext> lis_all = new TreeSet<>(cmp);
								SortedSet<VariantContext> lis_refseq = new TreeSet<>(cmp);
								SortedSet<VariantContext> lis_enst = new TreeSet<>(cmp);
								
								for(GeneTranscript gene_transcript:gene2variants.keySet() )
									{
									if(!geneName.equals(gene_transcript.geneName)) continue;
									lis_all.addAll(gene2variants.get(gene_transcript));
									if(gene_transcript.transcriptName.startsWith("NM_"))
										{
										lis_nm.addAll(gene2variants.get(gene_transcript));
										}
									if(! gene_transcript.transcriptName.startsWith("ENST"))
										{
										lis_refseq.addAll(gene2variants.get(gene_transcript));
										}
									if( gene_transcript.transcriptName.startsWith("ENST"))
										{
										lis_enst.addAll(gene2variants.get(gene_transcript));
										}
									}
								dump(zout,
										geneName+"_ALL_TRANSCRIPTS",
										samples,
										new ArrayList<VariantContext>(lis_all)
										);
								dump(zout,
										geneName+"_ALL_NM",
										samples,
										new ArrayList<VariantContext>(lis_nm)
										);
								dump(zout,
										geneName+"_ALL_REFSEQ",
										samples,
										new ArrayList<VariantContext>(lis_refseq)
										);
								dump(zout,
										geneName+"_ALL_ENST",
										samples,
										new ArrayList<VariantContext>(lis_enst)
										);
								}
							
							if(ctx1==null) break;
							gene2variants.clear();
							prev_chrom = ctx1.getContig();
							}
						Set<GeneTranscript> seen_names=new HashSet<>();
						for(VepPredictionParser.VepPrediction pred: vepPredParser.getPredictions(ctx1))
							{
							String geneName= pred.getSymbol();
							if(geneName==null || geneName.trim().isEmpty()) continue;
							
							
							if(this.gene2seen!=null)
								{
								if(!this.gene2seen.containsKey(geneName)) continue;
								
								}
							
							
							String transcriptName = pred.getFeature();
							if(transcriptName==null || transcriptName.trim().isEmpty())
								{
								LOG.info("No transcript in "+ctx1);
								continue;
								}
							
							GeneTranscript geneTranscript = new GeneTranscript(geneName, transcriptName);
							
							if(seen_names.contains(geneTranscript)) continue;
							boolean ok=false;
							for(SequenceOntologyTree.Term so:pred.getSOTerms())
								{
								if(acn.contains(so))
									{
									ok=true;
									}
								}
							if(!ok) continue;
							
							List<VariantContext> L = gene2variants.get(geneTranscript);
							if(L==null)
								{
								L=new ArrayList<>();
								gene2variants.put(geneTranscript,L);
								}
							L.add(ctx1);
							seen_names.add(geneTranscript);
							if(this.gene2seen!=null)
								{
								this.gene2seen.put(geneTranscript.geneName, Boolean.TRUE);
								}
							}
						}
					
					if(this.gene2seen!=null)
						{
						final List<VariantContext> emptylist = Collections.emptyList();
						for(String gene:this.gene2seen.keySet())
							{
							if(this.gene2seen.get(gene).equals(Boolean.TRUE)) continue;
							LOG.warn("Gene not found : "+gene);
							dump(zout,
									gene+"_000000000000000.txt",
									samples,
									emptylist
									);
							}
						}
					
					progress.finish();
					
					zout.finish();
					fout.flush();
					zout.flush();
					
					return RETURN_OK;
					}
				catch(Exception err)
					{
					return wrapException(err);
					}
				finally
					{
					CloserUtil.close(in);
					CloserUtil.close(zout);
					CloserUtil.close(fout);
					}
				}
		 	
		}
	public static void main(String[] args)
		{
		new VcfBurden().instanceMainWithExit(args);
		}
	}
